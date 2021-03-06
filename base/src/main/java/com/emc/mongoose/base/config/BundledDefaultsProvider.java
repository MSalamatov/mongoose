package com.emc.mongoose.base.config;

import static com.emc.mongoose.base.Constants.APP_NAME;
import static com.emc.mongoose.base.Constants.PATH_DEFAULTS;

import com.github.akurilov.confuse.io.json.JsonConfigProviderBase;
import java.io.InputStream;

public class BundledDefaultsProvider extends JsonConfigProviderBase {

  @Override
  protected final InputStream configInputStream() {
    return getClass().getResourceAsStream("/" + PATH_DEFAULTS);
  }

  @Override
  public final String id() {
    return APP_NAME;
  }
}
