package com.igsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.igsl.mybatis.CustomField;

public class ExportResult {
	
	public static class ResultItem {
		String errorMessage;
		List<String> files = new ArrayList<>();
		public String getErrorMessage() {
			return errorMessage;
		}
		public void setErrorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
		}
		public List<String> getFiles() {
			return files;
		}
		public void setFiles(List<String> files) {
			this.files = files;
		}
	}
	
	/**
	 * Key is customfield_[field id]-[context id]
	 * Value is list of files found
	 */
	private Map<String, ResultItem> resultMap = new TreeMap<>();
	
	public void addItem(CustomField cf, String contextId, String errorMessage, String file) {
		String name = "Field: " + cf.getFieldName() + " File: customfield_" + cf.getFieldId() + "-" + contextId;
		if (!resultMap.containsKey(name)) {
			resultMap.put(name, new ResultItem());
		}
		if (errorMessage != null) {
			resultMap.get(name).setErrorMessage(errorMessage);
		}
		if (file != null) {
			resultMap.get(name).getFiles().add(file);
		}
	}
	
	public void addAll(ExportResult er) {
		resultMap.putAll(er.getResultMap());
	}
	
	public Map<String, ResultItem> getResultMap() {
		return resultMap;
	}

	public void setResultMap(Map<String, ResultItem> resultMap) {
		this.resultMap = resultMap;
	}
	
}
