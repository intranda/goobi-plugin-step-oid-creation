package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.goobi.beans.LogEntry;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HttpClientHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class OidStepPlugin implements IStepPluginVersion2 {

    @Getter
    private String title = "intranda_step_oid";

    @Getter
    private Step step;
    Process process;
    Prefs prefs;
    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    @Getter
    private String pagePath = null;

    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private int interfaceVersion = 0;

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public boolean execute() {
        if (run() != PluginReturnValue.FINISH) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
        prefs = process.getRegelsatz().getPreferences();

    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public PluginReturnValue run() {

        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);

        String url = config.getString("url", "http://example.com");
        String username = config.getString("username");
        String password = config.getString("password");
        String headername = config.getString("headerparam", "Accept");
        String headervalue = config.getString("headerValue", "application/json");
        // open metadata file
        MetadataType identifierType = prefs.getMetadataTypeByName("CatalogIDDigital");
        MetadataType contentIdsType = prefs.getMetadataTypeByName("_urn");
        int numberOfOids = 0;

        try {
            Fileformat fileformat = process.readMetadataFile();
            // get main object
            DocStruct logical = fileformat.getDigitalDocument().getLogicalDocStruct();
            // check if main element contains a OID

            List<? extends Metadata> mdl = logical.getAllMetadataByType(identifierType);
            Metadata identifier = null;
            boolean skipMainElement = false;
            if (!mdl.isEmpty()) {
                identifier = mdl.get(0);
                if (identifier.getValue().matches("\\d+") && identifier.getValue().length() <= 9) {
                    skipMainElement = true;
                }
            }

            if (!skipMainElement) {
                numberOfOids++;
            }

            // get page objects
            DocStruct physical = fileformat.getDigitalDocument().getPhysicalDocStruct();
            List<DocStruct> pageList = physical.getAllChildren();

            // get OIDs only for objects without ids
            for (DocStruct page : pageList) {
                List<? extends Metadata> urns = page.getAllMetadataByType(contentIdsType);
                if (urns.isEmpty()) {
                    numberOfOids++;
                }
            }
            if (numberOfOids == 0) {
                // all fields contain OIDs, finish
                return PluginReturnValue.FINISH;
            }
            // request number of pages + 1
            if (numberOfOids != pageList.size() + 1) {
                // WARNING; we update an existing object
                LogEntry entry = LogEntry.build(process.getId()).withContent("OID request was executed multiple times.").withType(LogType.INFO);
                ProcessManager.saveLogEntry(entry);
            }
            numberOfOids = pageList.size() + 1;

            // get new identifiers from list
            String response = getStringFromUrl(url + numberOfOids, username, password, headername, headervalue);
            JsonElement jsonTree = JsonParser.parseString(response);

            JsonObject jsonObject = jsonTree.getAsJsonObject();
            JsonElement oids = jsonObject.get("oids");
            JsonArray values = oids.getAsJsonArray();

            int counter = 0;

            if (!skipMainElement) {
                Long oid = values.get(counter).getAsLong();
                if (identifier == null) {
                    try {
                        identifier = new Metadata(identifierType);
                        logical.addMetadata(identifier);
                    } catch (MetadataTypeNotAllowedException | DocStructHasNoTypeException e) {
                        log.error(e);
                    }
                }
                identifier.setValue(String.valueOf(oid));
                // also write it to the physical object
                try {
                    Metadata urn = new Metadata(contentIdsType);
                    urn.setValue(String.valueOf(oid));
                    physical.addMetadata(urn);
                } catch (MetadataTypeNotAllowedException | DocStructHasNoTypeException e) {
                    log.error(e);
                }
                counter++;
            }

            // get all folders to check and rename images
            Map<Path, List<Path>> allFolderAndAllFiles = process.getAllFolderAndFiles();
            // check size of folders, remove them if they don't match the expected number of files
            for (Path p : allFolderAndAllFiles.keySet()) {
                List<Path> files = allFolderAndAllFiles.get(p);
                if (pageList.size() != files.size()) {
                    files = Collections.emptyList();
                }
            }

            for (DocStruct page : pageList) {
                List<? extends Metadata> urns = page.getAllMetadataByType(contentIdsType);
                if (urns.isEmpty()) {
                    try {
                        Metadata urn = new Metadata(contentIdsType);
                        Long oid = values.get(counter).getAsLong();
                        urn.setValue(String.valueOf(oid));
                        page.addMetadata(urn);
                        // rename images to OID.extension
                        String oldFilename = page.getImageName();
                        Path f = Paths.get(oldFilename).getFileName();
                        oldFilename = f.toString();
                        int dot = oldFilename.lastIndexOf('.');
                        String basename = (dot == -1) ? oldFilename : oldFilename.substring(0, dot);
                        String extension = (dot == -1) ? "" : oldFilename.substring(dot + 1);

                        for (Path currentFolder : allFolderAndAllFiles.keySet()) {
                            // check files in current folder
                            List<Path> files = allFolderAndAllFiles.get(currentFolder);
                            for (Path file : files) {
                                String filenameToCheck = file.getFileName().toString();
                                String filenamePrefixToCheck = filenameToCheck.substring(0, filenameToCheck.lastIndexOf("."));
                                String fileExtension = filenameToCheck.substring(filenameToCheck.lastIndexOf(".") + 1);
                                // find the current file the folder
                                if (filenamePrefixToCheck.equals(basename)) {
                                    // found file to rename, create new file name
                                    Path tmpFileName = Paths.get(currentFolder.toString(), urn.getValue() + "." + fileExtension);
                                    try {
                                        StorageProvider.getInstance().move(file, tmpFileName);
                                    } catch (IOException e) {
                                        log.error(e);
                                    }
                                }
                            }
                        }

                        // write new image names to page objects
                        page.setImageName(urn.getValue() + "." + extension);

                        counter++;
                    } catch (MetadataTypeNotAllowedException | DocStructHasNoTypeException e) {
                        log.error(e);
                    }
                }
            }

            // save metadata file
            process.writeMetadataFile(fileformat);

        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
        }
        return PluginReturnValue.FINISH;
    }

    static String getStringFromUrl(String url, String username, String password, String headerParam, String headerParamValue) {
        String response = "";
        CloseableHttpClient client = null;
        HttpGet method = new HttpGet(url);

        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(new AuthScope(null, -1), new UsernamePasswordCredentials(username, password));
            client = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build();

        } else {
            client = HttpClientBuilder.create().build();
        }

        if (headerParam != null) {
            // add header parameter
            method.setHeader(headerParam, headerParamValue);
        }

        try {
            response = client.execute(method, HttpClientHelper.stringResponseHandler);
        } catch (IOException e) {
            log.error("Cannot execute URL " + url, e);
        } finally {
            method.releaseConnection();

            if (client != null) {
                try {
                    client.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
        return response;
    }

}
