package com.igsl.json;

/**
 * Checklist template as JSON
 * 
 * These are found in ScriptRunner listeners to replace checklist custom field content.
 */
public class JsonChecklistItem {
	private String name;
	private boolean mandatory;
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public boolean isMandatory() {
		return mandatory;
	}
	public void setMandatory(boolean mandatory) {
		this.mandatory = mandatory;
	}
}
