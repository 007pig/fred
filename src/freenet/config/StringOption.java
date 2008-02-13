/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.support.api.StringCallback;

public class StringOption extends Option {

	final String defaultValue;
	final StringCallback cb;
	private String currentValue;
	
	public StringOption(SubConfig conf, String optionName, String defaultValue, int sortOrder, 
			boolean expert, boolean forceWrite, String shortDesc, String longDesc, StringCallback cb) {
		super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DATA_TYPE_STRING);
		this.defaultValue = defaultValue;
		this.cb = cb;
		this.currentValue = defaultValue;
	}
	
	/** Get the current value. This is the value in use if we have finished
	 * initialization, otherwise it is the value set at startup (possibly the default). */
	public String getValue() {
		if(config.hasFinishedInitialization())
			return currentValue = cb.get();
		else return currentValue;
	}

	public void setValue(String val) throws InvalidConfigValueException {
		cb.set(val);
		this.currentValue = val; // Callbacks are in charge of ensuring it matches with possibleValues
	}
	
	public String getValueString() {
		return getValue();
	}

	public void setInitialValue(String val) throws InvalidConfigValueException {
		this.currentValue = val;
	}

	public boolean isDefault() {
		getValue();
		return currentValue.equals(defaultValue);
	}
	
	public void setDefault() {
		currentValue = defaultValue;
	}
	
	public String getDefault(){
		return defaultValue;
	}
}
