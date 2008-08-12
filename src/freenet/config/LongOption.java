/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.l10n.L10n;
import freenet.support.Fields;
import freenet.support.SizeUtil;
import freenet.support.api.LongCallback;

/** Long config variable */
public class LongOption extends Option {

	final long defaultValue;
	final LongCallback cb;
	private long currentValue;
	// Cache it mostly so that we can keep SI units
	private String cachedStringValue;

	public LongOption(SubConfig conf, String optionName, long defaultValue, String defaultValueString, 
			int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DATA_TYPE_NUMBER);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
		this.cachedStringValue = defaultValueString;
	}
	
	public LongOption(SubConfig conf, String optionName, String defaultValueString, 
			int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, LongCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DATA_TYPE_NUMBER);
		this.defaultValue = Fields.parseSILong(defaultValueString);
		this.cb = cb;
		this.currentValue = defaultValue;
		this.cachedStringValue = defaultValueString;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public long getValue() {
		if(config.hasFinishedInitialization()) {
			long val = cb.get();
			if(currentValue != val) {
				currentValue = val;
				cachedStringValue = null;
			}
		}
		return currentValue;
	}
	
	public void setValue(String val) throws InvalidConfigValueException {
		long x;
		try{
			// FIXME: don't strip, parse properly!
			x = Fields.parseSILong(SizeUtil.stripBytesEtc(val));
		}catch (NumberFormatException e) {
			throw new InvalidConfigValueException(l10n("parseError", "val", val));
		}
		cb.set(x);
		cachedStringValue = val;
		currentValue = x;
	}
	
	public String getValueString() {
		long l = getValue();
		if(cachedStringValue != null) 
			return cachedStringValue;
		else 
			return Long.toString(l);
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		long x;
		try{
			x = Fields.parseSILong(val);
		}catch (NumberFormatException e) {
			throw new InvalidConfigValueException(l10n("parseError", "val", val));
		}
		cachedStringValue = val;
		currentValue = x;
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("LongOption."+key, pattern, value);
	}

	public boolean isDefault() {
		getValue();
		return currentValue == defaultValue;
	}
	
	public String getDefault() {
		return Long.toString(defaultValue);
	}

	public void setDefault() {
		currentValue = defaultValue;
	}
	
}
