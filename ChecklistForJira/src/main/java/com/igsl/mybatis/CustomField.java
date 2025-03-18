package com.igsl.mybatis;

import java.util.List;

public class CustomField {
	private String fieldId;
	private String fieldName;
	private String fieldType;
	private List<Source> sourceList;
	public String getFieldId() {
		return fieldId;
	}
	public void setFieldId(String fieldId) {
		this.fieldId = fieldId;
	}
	public String getFieldName() {
		return fieldName;
	}
	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}
	public String getFieldType() {
		return fieldType;
	}
	public void setFieldType(String fieldType) {
		this.fieldType = fieldType;
	}
	public List<Source> getSourceList() {
		return sourceList;
	}
	public void setSourceList(List<Source> sourceList) {
		this.sourceList = sourceList;
	}
}
