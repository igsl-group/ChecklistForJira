package com.igsl.json;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
	{
        "id": 2,
        "name": "Global 1",
        "mandatory": true,
        "rank": 0,
        "statusId": "none",
        "dueDate": null,
        "isHeader": false,
        "checked": false,
        "priorityId": null,
        "disabled": false,
        "assigneeIds": []
    }
 */
public class ChecklistItem {
	private static final String STATUS_NONE = "none";
	private static final String STATUS_OPEN = "open";
	private static final String STATUS_NA = "notApplicable";
	private static final Pattern NAME_PATTERN = Pattern.compile("(.+)\n>>\n(.+)");
	private int id;
	private String name;
	private boolean mandatory;
	private String statusId;
	@JsonProperty("isHeader")
	private boolean isHeader;
	private boolean checked;
	private boolean disabled;
	public static String convert(List<ChecklistItem> items) {
		StringBuilder result = new StringBuilder();
		if (items != null) {
			for (ChecklistItem item : items) {
				if (!item.isDisabled()) {
					result.append("\n");
					if (item.isHeader()) {
						result.append("--- ").append(item.getName());
					} else {
						if (item.isMandatory()) {
							result.append("* ");
						}
						if (item.isChecked()) {
							result.append("[x] ");
						}
						if (item.getStatusId() == null || STATUS_NA.equals(item.getStatusId()) || STATUS_NONE.equals(item.getStatusId())) {
							result.append("[").append(STATUS_OPEN).append("] ");
						} else {
							result.append("[").append(item.getStatusId()).append("] ");
						}
						Matcher matcher = NAME_PATTERN.matcher(item.getName());
						if (matcher.matches()) {
							result.append(matcher.group(1)).append("\n>>").append(matcher.group(2));
						} else {
							result.append(item.getName());
						}
					}
				}
			}
		}
		if (result.length() > 1) {
			// Exclude initial newline
			return result.toString().substring(1);
		}
		return result.toString();
	}
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb	.append("id: [").append(id).append("] ")
			.append("name: [").append(name).append("] ")
			.append("mandatory: [").append(mandatory).append("] ")
			.append("statusId: [").append(statusId).append("] ")
			.append("isHeader: [").append(isHeader).append("] ")
			.append("checked: [").append(checked).append("] ")
			.append("disabled: [").append(disabled).append("] ");
		return sb.toString();
	}
	
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
	public String getStatusId() {
		return statusId;
	}
	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}
	public boolean isHeader() {
		return isHeader;
	}
	public void setHeader(boolean isHeader) {
		this.isHeader = isHeader;
	}
	public boolean isChecked() {
		return checked;
	}
	public void setChecked(boolean checked) {
		this.checked = checked;
	}
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public boolean isDisabled() {
		return disabled;
	}
	public void setDisabled(boolean disabled) {
		this.disabled = disabled;
	}
}
