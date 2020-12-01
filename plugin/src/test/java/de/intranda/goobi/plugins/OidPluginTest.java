package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.easymock.EasyMock;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Ruleset;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.junit.Before;
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
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigurationHelper.class, ProcessManager.class, MetadataManager.class,
    OidStepPlugin.class })
@PowerMockIgnore({ "javax.management.*", "javax.net.ssl.*" })

public class OidPluginTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private File processDirectory;
    private File metadataDirectory;
    private Process process;
    private Prefs prefs;
    private String resourcesFolder;

    @Before
    public void setUp() throws Exception {
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        metadataDirectory = folder.newFolder("metadata");

        processDirectory = new File(metadataDirectory + File.separator + "1");
        processDirectory.mkdirs();
        String metadataDirectoryName = metadataDirectory.getAbsolutePath() + File.separator;

        // copy meta.xml
        Path metaSource = Paths.get(resourcesFolder + "meta.xml");
        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");

        Files.copy(metaSource, metaTarget);

        PowerMock.mockStatic(ConfigurationHelper.class);
        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getMetsEditorLockingTime()).andReturn(1800000l).anyTimes();
        EasyMock.expect(configurationHelper.isAllowWhitespacesInFolder()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseProxy()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.getGoobiContentServerTimeOut()).andReturn(60000).anyTimes();
        EasyMock.expect(configurationHelper.getMetadataFolder()).andReturn(metadataDirectoryName).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andReturn("00469418X_media").anyTimes();
        EasyMock.expect(configurationHelper.isUseMasterDirectory()).andReturn(true).anyTimes();
        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();

        EasyMock.expect(configurationHelper.getNumberOfMetaBackups()).andReturn(0).anyTimes();
        EasyMock.replay(configurationHelper);

        PowerMock.mockStatic(VariableReplacer.class);
        EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn("00469418X_media").anyTimes();
        PowerMock.replay(VariableReplacer.class);
        prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "vd18.xml");
        Fileformat ff = new MetsMods(prefs);
        ff.read(metaTarget.toString());

        PowerMock.mockStatic(MetadatenHelper.class);
        EasyMock.expect(MetadatenHelper.getMetaFileType(EasyMock.anyString())).andReturn("mets").anyTimes();
        EasyMock.expect(MetadatenHelper.getFileformatByName(EasyMock.anyString(), EasyMock.anyObject())).andReturn(ff).anyTimes();
        EasyMock.expect(MetadatenHelper.getMetadataOfFileformat(EasyMock.anyObject(), EasyMock.anyBoolean()))
        .andReturn(Collections.emptyMap())
        .anyTimes();
        PowerMock.replay(MetadatenHelper.class);

        PowerMock.mockStatic(MetadataManager.class);
        MetadataManager.updateMetadata(1, Collections.emptyMap());
        MetadataManager.updateJSONMetadata(1, Collections.emptyMap());
        PowerMock.replay(MetadataManager.class);
        PowerMock.replay(ConfigurationHelper.class);

        PowerMock.mockStatic(OidStepPlugin.class);

        EasyMock.expect(OidStepPlugin.getStringFromUrl(EasyMock.anyString(), EasyMock.anyString(), EasyMock.anyString(), EasyMock.anyString(),
                EasyMock.anyString())).andReturn("{\"oids\":[300006252,300006253,300006254,300006255,300006256,300006257]}").anyTimes();
        process = getProcess();

        Ruleset ruleset = PowerMock.createMock(Ruleset.class);
        ruleset.setTitel("vd18");
        ruleset.setDatei("vd18.xml");
        process.setRegelsatz(ruleset);
        EasyMock.expect(ruleset.getPreferences()).andReturn(prefs).anyTimes();
        PowerMock.replay(ruleset);

        PowerMock.replay(OidStepPlugin.class);

    }

    @Test
    public void testConstructor() throws IOException {
        OidStepPlugin plugin = new OidStepPlugin();
        assertNotNull(plugin);
    }

    @Test
    public void testInit() {
        OidStepPlugin plugin = new OidStepPlugin();
        assertNotNull(plugin);
        Step step = process.getSchritte().get(0);
        plugin.initialize(step, "");

        assertEquals("test step", plugin.getStep().getTitel());
        assertEquals("00469418X", plugin.process.getTitel());
        assertNotNull(plugin.prefs);

    }

    @Test
    public void testExecute() throws Exception {
        OidStepPlugin plugin = new OidStepPlugin();
        assertNotNull(plugin);
        Step step = process.getSchritte().get(0);

        plugin.initialize(step, "");

        // check old file names

        String[] filesInMasterFolder = new File(processDirectory.getAbsolutePath() + "/images/00469418X_master").list();
        Arrays.sort(filesInMasterFolder);
        assertEquals("00000001.tif", filesInMasterFolder[0]);

        String[] filesInMediaFolder = new File(processDirectory.getAbsolutePath() + "/images/00469418X_media").list();
        Arrays.sort(filesInMediaFolder);
        assertEquals("00000001.jpg", filesInMediaFolder[0]);

        assertTrue(plugin.execute());

        // open created file
        Fileformat ff = new MetsMods(prefs);
        ff.read(processDirectory.getAbsolutePath() +"/meta.xml");
        // check oids in mets file

        DocStruct log = ff.getDigitalDocument().getLogicalDocStruct();
        assertEquals("300006252", log.getAllMetadataByType(prefs.getMetadataTypeByName("CatalogIDDigital")).get(0).getValue());

        // check Page DocStructs
        DocStruct page = ff.getDigitalDocument().getPhysicalDocStruct().getAllChildren().get(0);
        assertEquals("300006253", page.getAllMetadataByType(prefs.getMetadataTypeByName("_urn")).get(0).getValue());
        assertEquals("300006253.jpg", page.getImageName());

        // check file names
        filesInMasterFolder = new File(processDirectory.getAbsolutePath() + "/images/00469418X_master").list();
        Arrays.sort(filesInMasterFolder);
        assertEquals("300006253.tif", filesInMasterFolder[0]);

        filesInMediaFolder = new File(processDirectory.getAbsolutePath() + "/images/00469418X_media").list();
        Arrays.sort(filesInMediaFolder);
        assertEquals("300006253.jpg", filesInMediaFolder[0]);

    }

    @Test
    public void testCreatePagination() throws Exception {
        Path metaSource = Paths.get(resourcesFolder + "meta2.xml");
        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");

        Files.copy(metaSource, metaTarget, StandardCopyOption.REPLACE_EXISTING);

        OidStepPlugin plugin = new OidStepPlugin();
        assertNotNull(plugin);
        Step step = process.getSchritte().get(0);

        plugin.initialize(step, "");
        assertTrue(plugin.execute());

        // open created file
        Fileformat ff = new MetsMods(prefs);
        ff.read(processDirectory.getAbsolutePath() +"/meta.xml");

        // check physical structMap

        DocStruct physical = ff.getDigitalDocument().getPhysicalDocStruct();
        assertNotNull(physical);

        DocStruct page = physical.getAllChildren().get(0);
        assertEquals("300006253", page.getAllMetadataByType(prefs.getMetadataTypeByName("_urn")).get(0).getValue());
        assertEquals("300006253.jpg", page.getImageName());


    }


    public Process getProcess() {
        Project project = new Project();
        project.setTitel("SampleProject");

        Process process = new Process();
        process.setTitel("00469418X");
        process.setProjekt(project);
        process.setId(1);
        List<Step> steps = new ArrayList<>();
        Step s1 = new Step();
        s1.setReihenfolge(1);
        s1.setProzess(process);
        s1.setTitel("test step");
        s1.setBearbeitungsstatusEnum(StepStatus.OPEN);
        User user = new User();
        user.setVorname("Firstname");
        user.setNachname("Lastname");
        s1.setBearbeitungsbenutzer(user);
        steps.add(s1);

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
        {
            File image1 = new File(masterDirectory.getAbsoluteFile(), "00000001.tif");
            File image2 = new File(masterDirectory.getAbsoluteFile(), "00000002.tif");
            File image3 = new File(masterDirectory.getAbsoluteFile(), "00000003.tif");
            File image4 = new File(masterDirectory.getAbsoluteFile(), "00000004.tif");
            File image5 = new File(masterDirectory.getAbsoluteFile(), "00000005.tif");
            image1.createNewFile();
            image2.createNewFile();
            image3.createNewFile();
            image4.createNewFile();
            image5.createNewFile();
        }
        // media folder
        File mediaDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_media");
        mediaDirectory.mkdir();
        {
            File image1 = new File(mediaDirectory.getAbsoluteFile(), "00000001.jpg");
            File image2 = new File(mediaDirectory.getAbsoluteFile(), "00000002.jpg");
            File image3 = new File(mediaDirectory.getAbsoluteFile(), "00000003.jpg");
            File image4 = new File(mediaDirectory.getAbsoluteFile(), "00000004.jpg");
            File image5 = new File(mediaDirectory.getAbsoluteFile(), "00000005.jpg");
            image1.createNewFile();
            image2.createNewFile();
            image3.createNewFile();
            image4.createNewFile();
            image5.createNewFile();
        }
    }
}
