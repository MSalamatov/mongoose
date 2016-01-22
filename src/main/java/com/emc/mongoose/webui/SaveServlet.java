package com.emc.mongoose.webui;
// mongoose-common.jar
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.conf.JsonConfigLoader;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
//
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
//
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Scanner;
/**
 * Created by gusakk on 12/28/14.
 */
public class SaveServlet
extends CommonServlet {
	//
	private final static Logger LOG = LogManager.getLogger();
	private final static String FNAME_CONF_SAVE = "config.txt";
	private final static File
		DIR_WEBAPP_CONF = Paths.get(
			RunTimeConfig.DIR_ROOT, Constants.DIR_WEBAPP, Constants.DIR_CONF
		).toFile();
	//	HTTP Headers
	private final static String CONTENT_TYPE = "Content-Type";
	private final static String CONTENT_LENGTH = "Content-Length";
	private final static String CONTENT_DISPOSITION = "Content-Disposition";
	//
	@Override
	public void doGet(final HttpServletRequest request, final HttpServletResponse response) {
		if (!request.getParameterMap().isEmpty()) {
			setupRunTimeConfig(request);
			saveConfigInSeparateFile();
			response.setStatus(HttpServletResponse.SC_OK);
			return;
		}
		final File fullFileName = new File(DIR_WEBAPP_CONF.toString() + File.separator + FNAME_CONF_SAVE);
		try {
			final PrintWriter writer = response.getWriter();
			final Scanner scanner = new Scanner(fullFileName);
			while(scanner.hasNextLine()) {
				String s = scanner.nextLine();
				writer.write(s + '\n');
			}
		} catch (final IOException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to write the response");
		}
		response.setHeader(CONTENT_TYPE, getServletContext().getMimeType(fullFileName.getName()));
		response.setHeader(CONTENT_LENGTH, String.valueOf(fullFileName.length()));
		response.setHeader(CONTENT_DISPOSITION, "attachment;filename=\"" + fullFileName.getName() + "\"");
	}
	//
	@Override
	public void doPost(final HttpServletRequest request, final HttpServletResponse response) {
		setupRunTimeConfig(request);
		new JsonConfigLoader(runTimeConfig).updateJsonCfgFile(
			Paths.get(RunTimeConfig.DIR_ROOT, Constants.DIR_CONF)
				.resolve(RunTimeConfig.FNAME_CONF).toFile()
		);
		updateLastRunTimeConfig(runTimeConfig);
		response.setStatus(HttpServletResponse.SC_OK);
	}
	//
	@Deprecated
	private void saveConfigInSeparateFile() {
		/*if (!DIR_WEBAPP_CONF.mkdirs()) {
			if (!DIR_WEBAPP_CONF.exists()) {
				LOG.error(Markers.ERR, "Failed to create folders for ui config");
				return;
			}
		}
		try (final FileWriter writer = new FileWriter(DIR_WEBAPP_CONF + File.separator + FNAME_CONF_SAVE)) {
			final PropertiesConfiguration props = new PropertiesConfiguration();
			for (String key : runTimeConfig.getMongooseKeys()) {
				props.setProperty(key, runTimeConfig.getProperty(key));
			}
			try {
				props.save(writer);
			} catch (final ConfigurationException e) {
				LogUtil.exception(LOG, Level.ERROR, e, "Configuration exception");
			}
		} catch (final IOException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to write properties to ui config file");
		}*/
	}
}
