package freenet.support.api;

import freenet.config.ConfigCallback;
import freenet.config.InvalidConfigValueException;

/**
 * A callback to be called when a config value of short type changes.
 * Also reports the current value.
 */
public abstract class ShortCallback extends ConfigCallback {

	/**
	 * Get the current, used value of the config variable.
	 */
	public abstract short get();
	
	/**
	 * Set the config variable to a new value.
	 * @param val The new value.
	 * @throws InvalidConfigOptionException If the new value is invalid for 
	 * this particular option.
	 */
	public abstract void set(short val) throws InvalidConfigValueException;
	
}
