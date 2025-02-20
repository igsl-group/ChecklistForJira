package com.igsl.json;

import java.util.List;

public class ChecklistTemplate {
	private String id;
	private String name;
	private String description;
	private int fieldConfigId;
	private int projectId;
	private int customFieldId;
	private String owner;
	private boolean importable;
	private boolean pinned;
	private List<ChecklistItem> items;
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public int getFieldConfigId() {
		return fieldConfigId;
	}
	public void setFieldConfigId(int fieldConfigId) {
		this.fieldConfigId = fieldConfigId;
	}
	public int getProjectId() {
		return projectId;
	}
	public void setProjectId(int projectId) {
		this.projectId = projectId;
	}
	public int getCustomFieldId() {
		return customFieldId;
	}
	public void setCustomFieldId(int customFieldId) {
		this.customFieldId = customFieldId;
	}
	public String getOwner() {
		return owner;
	}
	public void setOwner(String owner) {
		this.owner = owner;
	}
	public boolean isImportable() {
		return importable;
	}
	public void setImportable(boolean importable) {
		this.importable = importable;
	}
	public boolean isPinned() {
		return pinned;
	}
	public void setPinned(boolean pinned) {
		this.pinned = pinned;
	}
	public List<ChecklistItem> getItems() {
		return items;
	}
	public void setItems(List<ChecklistItem> items) {
		this.items = items;
	}
}
