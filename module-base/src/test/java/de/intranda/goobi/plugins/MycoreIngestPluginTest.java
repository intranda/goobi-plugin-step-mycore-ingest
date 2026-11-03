package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.easymock.EasyMock;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Ruleset;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.ProcessManager;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigurationHelper.class, ProcessManager.class,
        MetadataManager.class })
@PowerMockIgnore({ "javax.management.*", "javax.xml.*", "org.xml.*", "org.w3c.*", "javax.net.ssl.*", "jdk.internal.reflect.*" })
public class MycoreIngestPluginTest {

    private static String resourcesFolder;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File processDirectory;
    private File metadataDirectory;
    private Process process;
    private Step step;
    private Prefs prefs;

    @BeforeClass
    public static void setUpClass() throws Exception {
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        String log4jFile = resourcesFolder + "log4j2.xml"; // for junit tests in eclipse

        System.setProperty("log4j.configurationFile", log4jFile);
    }

    @Test
    public void testConstructor() throws Exception {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        assertNotNull(plugin);
    }

    @Test
    public void testInit() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        plugin.initialize(step, "something");
        assertEquals(step.getTitel(), plugin.getStep().getTitel());
    }

    @Test
    public void testVersion() throws IOException {
        String s = "xyz";
        assertNotNull(s);
    }

    @Test
    public void testInitReadsDebugPaths() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        plugin.initialize(step, "something");
        assertEquals("/opt/digiverso/debug/{processtitle}_before.xml", plugin.getDebugMetsBeforeXslt());
        assertEquals("/opt/digiverso/debug/{processtitle}_after.xml", plugin.getDebugMetsAfterXslt());
    }

    @Test
    public void testResolveDebugPathsWithoutConfigDoesNotUseReplacer() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        VariableReplacer replacer = EasyMock.createStrictMock(VariableReplacer.class);
        EasyMock.replay(replacer);

        Path[] paths = plugin.resolveDebugPaths("", "", replacer);

        assertNull(paths[0]);
        assertNull(paths[1]);
        EasyMock.verify(replacer);
    }

    @Test
    public void testResolveDebugPathsReplacesBothInOneCall() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        VariableReplacer replacer = EasyMock.createStrictMock(VariableReplacer.class);
        EasyMock.expect(replacer.replace("/debug/{datetime.HHmmss}_before.xml\n/debug/{datetime.HHmmss}_after.xml"))
                .andReturn("/debug/120000_before.xml\n/debug/120000_after.xml")
                .once();
        EasyMock.replay(replacer);

        Path[] paths = plugin.resolveDebugPaths("/debug/{datetime.HHmmss}_before.xml", "/debug/{datetime.HHmmss}_after.xml",
                replacer);

        assertEquals(Paths.get("/debug/120000_before.xml"), paths[0]);
        assertEquals(Paths.get("/debug/120000_after.xml"), paths[1]);
        EasyMock.verify(replacer);
    }

    @Test
    public void testResolveDebugPathsWithOnlyAfterConfigured() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        VariableReplacer replacer = EasyMock.createMock(VariableReplacer.class);
        EasyMock.expect(replacer.replace("\n/debug/after.xml")).andReturn("\n/debug/after.xml").once();
        EasyMock.replay(replacer);

        Path[] paths = plugin.resolveDebugPaths("", "/debug/after.xml", replacer);

        assertNull(paths[0]);
        assertEquals(Paths.get("/debug/after.xml"), paths[1]);
    }

    @Test
    public void testResolveDebugPathsWithInvalidPath() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        VariableReplacer replacer = EasyMock.createMock(VariableReplacer.class);
        EasyMock.expect(replacer.replace(EasyMock.anyString())).andReturn("/debug/in\u0000valid.xml\n/debug/after.xml").once();
        EasyMock.replay(replacer);

        Path[] paths = plugin.resolveDebugPaths("/debug/before.xml", "/debug/after.xml", replacer);

        assertNull(paths[0]);
        assertEquals(Paths.get("/debug/after.xml"), paths[1]);
    }

    @Test
    public void testSaveDebugFileCreatesFolders() throws IOException {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        Path target = folder.getRoot().toPath().resolve("debug").resolve("process").resolve("mets.xml");

        plugin.saveDebugFile(target, "<mets/>".getBytes(StandardCharsets.UTF_8));

        assertEquals("<mets/>", Files.readString(target));
    }

    @Test
    public void testSaveDebugFileOverwritesExistingFile() throws IOException {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        Path target = folder.newFile("mets.xml").toPath();
        Files.writeString(target, "<old/>");

        plugin.saveDebugFile(target, "<new/>".getBytes(StandardCharsets.UTF_8));

        assertEquals("<new/>", Files.readString(target));
    }

    @Test
    public void testSaveDebugFileWithoutTarget() {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        plugin.saveDebugFile(null, "<mets/>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testSaveDebugFileFailureDoesNotThrow() throws IOException {
        MycoreIngestStepPlugin plugin = new MycoreIngestStepPlugin();
        Path regularFile = folder.newFile("not-a-folder").toPath();
        Path target = regularFile.resolve("mets.xml");

        plugin.saveDebugFile(target, "<mets/>".getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.isRegularFile(regularFile));
        assertFalse(Files.exists(target));
    }

    @Before
    public void setUp() throws Exception {
        metadataDirectory = folder.newFolder("metadata");
        processDirectory = new File(metadataDirectory + File.separator + "1");
        processDirectory.mkdirs();
        String metadataDirectoryName = metadataDirectory.getAbsolutePath() + File.separator;
        Path metaSource = Paths.get(resourcesFolder, "meta.xml");
        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");
        Files.copy(metaSource, metaTarget);

        Path anchorSource = Paths.get(resourcesFolder, "meta_anchor.xml");
        Path anchorTarget = Paths.get(processDirectory.getAbsolutePath(), "meta_anchor.xml");
        Files.copy(anchorSource, anchorTarget);

        PowerMock.mockStatic(ConfigurationHelper.class);
        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getMetsEditorLockingTime()).andReturn(1800000l).anyTimes();
        EasyMock.expect(configurationHelper.isAllowWhitespacesInFolder()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseProxy()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.getGoobiContentServerTimeOut()).andReturn(60000).anyTimes();
        EasyMock.expect(configurationHelper.getMetadataFolder()).andReturn(metadataDirectoryName).anyTimes();
        EasyMock.expect(configurationHelper.getRulesetFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andReturn("00469418X_media").anyTimes();
        EasyMock.expect(configurationHelper.isUseMasterDirectory()).andReturn(true).anyTimes();
        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getNumberOfMetaBackups()).andReturn(0).anyTimes();
        EasyMock.replay(configurationHelper);

        PowerMock.mockStatic(VariableReplacer.class);
        EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn("00469418X_media").anyTimes();
        PowerMock.replay(VariableReplacer.class);
        prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "ruleset.xml");
        Fileformat ff = new MetsMods(prefs);
        ff.read(metaTarget.toString());

        PowerMock.mockStatic(MetadatenHelper.class);
        EasyMock.expect(MetadatenHelper.getMetaFileType(EasyMock.anyString())).andReturn("mets").anyTimes();
        EasyMock.expect(MetadatenHelper.getFileformatByName(EasyMock.anyString(), EasyMock.anyObject())).andReturn(ff).anyTimes();
        EasyMock.expect(MetadatenHelper.getMetadataOfFileformat(EasyMock.anyObject()))
                .andReturn(Collections.emptyMap())
                .anyTimes();
        PowerMock.replay(MetadatenHelper.class);

        PowerMock.mockStatic(MetadataManager.class);
        MetadataManager.updateMetadata(1, Collections.emptyMap());
        PowerMock.replay(MetadataManager.class);
        PowerMock.replay(ConfigurationHelper.class);

        process = getProcess();

        Ruleset ruleset = PowerMock.createMock(Ruleset.class);
        ruleset.setTitel("ruleset");
        ruleset.setDatei("ruleset.xml");
        EasyMock.expect(ruleset.getDatei()).andReturn("ruleset.xml").anyTimes();
        process.setRegelsatz(ruleset);
        EasyMock.expect(ruleset.getPreferences()).andReturn(prefs).anyTimes();
        PowerMock.replay(ruleset);

    }

    public Process getProcess() {
        Project project = new Project();
        project.setTitel("MycoreIngestProject");

        Process process = new Process();
        process.setTitel("00469418X");
        process.setProjekt(project);
        process.setId(1);
        List<Step> steps = new ArrayList<>();
        step = new Step();
        step.setReihenfolge(1);
        step.setProzess(process);
        step.setTitel("test step");
        step.setBearbeitungsstatusEnum(StepStatus.OPEN);
        User user = new User();
        user.setVorname("Firstname");
        user.setNachname("Lastname");
        step.setBearbeitungsbenutzer(user);
        steps.add(step);

        process.setSchritte(steps);

        try {
            createProcessDirectory(processDirectory);
        } catch (IOException e) {
        }

        return process;
    }

    private void createProcessDirectory(File processDirectory) throws IOException {

        // image folder
        File imageDirectory = new File(processDirectory.getAbsolutePath(), "images");
        imageDirectory.mkdir();
        // master folder
        File masterDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_master");
        masterDirectory.mkdir();

        // media folder
        File mediaDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_media");
        mediaDirectory.mkdir();

        // TODO add some file
    }
}
