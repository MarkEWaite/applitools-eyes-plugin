package com.applitools.jenkins;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import jenkins.model.ArtifactManager;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.client.HttpClient;
import hudson.model.JobProperty;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.util.VirtualFile;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

import static com.applitools.jenkins.ApplitoolsBuildWrapper.ARTIFACT_PATHS;
import static com.applitools.jenkins.ApplitoolsEnvironmentUtil.APPLITOOLS_BATCH_ID;


/**
 * Common methods to be used other parts of the plugin
 */
public class ApplitoolsCommon {

    public final static String APPLITOOLS_DEFAULT_URL = "https://eyes.applitools.com";
    public final static boolean NOTIFY_ON_COMPLETION = true;
    public final static String BATCH_NOTIFICATION_PATH = "/api/sessions/batches/%s/close/bypointerid";
    public final static String APPLITOOLS_ARTIFACT_FOLDER = ".applitools";
    public final static String APPLITOOLS_ARTIFACT_PREFIX = "APPLITOOLS";
    public final static Pattern artifactRegexp = Pattern.compile(ApplitoolsCommon.APPLITOOLS_ARTIFACT_PREFIX + "_(.*)");
    private static final Logger logger = Logger.getLogger(ApplitoolsStatusDisplayAction.class.getName());
    private static Map<String, String> env;

    @SuppressWarnings("rawtypes")
    public static void integrateWithApplitools(Run run, String serverURL, boolean notifyOnCompletion,
                                               String applitoolsApiKey, boolean dontCloseBatches, boolean deleteBatch
    ) throws IOException
    {
        updateProjectProperties(run, serverURL, notifyOnCompletion, applitoolsApiKey, dontCloseBatches, deleteBatch);
        addApplitoolsActionToBuild(run);
        run.save();
    }
    @SuppressWarnings("rawtypes")
    private static void updateProjectProperties(Run run, String serverURL, boolean notifyOnCompletion,
                                                String applitoolsApiKey, boolean dontCloseBatches, boolean deleteBatch
                                               ) throws IOException
    {
        boolean found = false;
        for (Object property:run.getParent().getAllProperties())
        {
            if (property instanceof ApplitoolsProjectConfigProperty)
            {
                ((ApplitoolsProjectConfigProperty)property).setServerURL(serverURL);
                ((ApplitoolsProjectConfigProperty)property).setNotifyOnCompletion(notifyOnCompletion);
                ((ApplitoolsProjectConfigProperty)property).setApplitoolsApiKey(applitoolsApiKey);
                ((ApplitoolsProjectConfigProperty)property).setDontCloseBatches(dontCloseBatches);
                ((ApplitoolsProjectConfigProperty)property).setDeleteBatch(deleteBatch);
                found = true;
                break;
            }
        }
        if (!found)
        {
            JobProperty jp = new ApplitoolsProjectConfigProperty(serverURL, notifyOnCompletion, applitoolsApiKey);
            run.getParent().addProperty(jp);
        }
        run.getParent().save();
    }

    private static void addApplitoolsActionToBuild(final Run<?,?> build)
    {
        ApplitoolsStatusDisplayAction buildAction = build.getAction(ApplitoolsStatusDisplayAction.class);
        if (buildAction == null) {
            buildAction = new ApplitoolsStatusDisplayAction(build);
            build.addAction(buildAction);
        }
    }

    public static void buildEnvVariablesForExternalUsage(Map<String, String> env, final Run<?,?> build, final TaskListener listener, FilePath workspace, Launcher launcher, String serverURL, String applitoolsApiKey, Map<String, String> artifacts)
    {
        ApplitoolsCommon.env = env;
        String projectName = build.getParent().getDisplayName();
        MutableBoolean isCustom = new MutableBoolean(false);
        String batchId = ApplitoolsStatusDisplayAction.generateBatchId(env, projectName, build.getNumber(), build.getTimestamp(), artifacts, isCustom);
        String filepath = ARTIFACT_PATHS.get(APPLITOOLS_ARTIFACT_PREFIX + "_" + APPLITOOLS_BATCH_ID);
        FilePath batchIdFilePath = workspace.child(filepath);
        if (isCustom.isTrue()){
            try {
                batchIdFilePath.write(batchId, StandardCharsets.UTF_8.name());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            archiveArtifacts(build, workspace, launcher, listener);
        }
        String batchName = projectName;
        ApplitoolsEnvironmentUtil.outputVariables(listener, env, serverURL, batchName, batchId, projectName, applitoolsApiKey);
        try {
            batchIdFilePath.delete();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void archiveArtifacts(Run<?,?> run, FilePath workspace, Launcher launcher,
                                        final TaskListener listener) {
        try {
            ArtifactManager artifactManager = run.getArtifactManager();
            artifactManager.archive(workspace, launcher, (BuildListener) listener, ARTIFACT_PATHS);
        } catch (InterruptedException | IOException ex) {
            listener.getLogger().println("Error archiving artifacts: " + ex.getMessage());
        }
    }

    public static Map<String, String> getEnv() { return env; }
    public static String getEnv(String key) { return env.get(key); }

    public static void closeBatch(Run<?,?> run, TaskListener listener, String serverURL,
                                  boolean notifyOnCompletion, String applitoolsApiKey, boolean deleteBatch)
            throws IOException {
        if (notifyOnCompletion && applitoolsApiKey != null && !applitoolsApiKey.isEmpty()) {
            String batchId = ApplitoolsStatusDisplayAction.generateBatchId(
                env,
                run.getParent().getDisplayName(),
                run.getNumber(),
                run.getTimestamp(),
                ApplitoolsCommon.checkApplitoolsArtifacts(
                    run.getArtifacts(),
                    run.getArtifactManager().root()
                )
            );
            HttpClient httpClient = HttpClientBuilder.create().build();
            URI targetUrl = null;
            try {
                targetUrl = new URIBuilder(serverURL)
                        .setPath(String.format(BATCH_NOTIFICATION_PATH, batchId))
                        .addParameter("apiKey", applitoolsApiKey)
                        .build();
            } catch (URISyntaxException e) {
                logger.warning("Couldn't build URI: " + e.getMessage());
            }

            if (deleteBatch) {
                HttpUriRequest deleteRequest = new HttpDelete(targetUrl);
                try {
                    listener.getLogger().printf("Batch notification called with %s%n", batchId);
                    int statusCode = httpClient.execute(deleteRequest).getStatusLine().getStatusCode();
                    listener.getLogger().println("Delete batch is done with " + statusCode + " status");
                } finally {
                    deleteRequest.abort();
                }
            }
        }

    }

    public static Map<String, String> checkApplitoolsArtifacts(List<? extends Run<?, ?>.Artifact> artifactList, VirtualFile file) {
        Map<String, String> result = new HashMap<>();
        if (!artifactList.isEmpty() && file != null) {
            for (Run<?,?>.Artifact artifact : artifactList) {
                String artifactFileName = artifact.getFileName();
                Matcher m = artifactRegexp.matcher(artifactFileName);
                if (m.find()) {
                    String artifactName = m.group(1);
                    try {
                        InputStream stream = file.child(artifactFileName).open();
                        String value = IOUtils.toString(stream, StandardCharsets.UTF_8).replaceAll(System.lineSeparator(), "");
                        result.put(artifactName, value);
                    } catch (java.io.IOException e) {
                        logger.warning("Couldn't get artifact " + artifactFileName + "." + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    private static String pluginVersion = null;

    public static String getPluginVersion() {
        if (pluginVersion == null) {
            pluginVersion = ApplitoolsCommon.class.getPackage().getImplementationVersion();
            try {
                Properties p = new Properties();
                InputStream is = ApplitoolsCommon.class.getClassLoader().getResourceAsStream("my.properties");
                if (is != null) {
                    p.load(is);
                    pluginVersion = p.getProperty("version", "");
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error getting plugin version", e);
            }
        }
        return pluginVersion;
    }
}
