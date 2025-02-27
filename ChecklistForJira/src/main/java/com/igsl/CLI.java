package com.igsl;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 * Houses the members for command line parsing
 */
public class CLI {
	
	// Enum so we can use switch on Option
	public static enum CLIOptions {
		EXPORT_FIELD(EXPORT_FIELD_OPTIONS, EXPORT_FIELD_OPTION), 
		TRIGGER_EXPORT(TRIGGER_EXPORT_OPTIONS, TRIGGER_EXPORT_OPTION),
		EXPORT_USAGE(EXPORT_USAGE_OPTIONS, EXPORT_USAGE_OPTION),
		EXPORT_WORKFLOW(EXPORT_WORKFLOW_OPTIONS, EXPORT_WORKFLOW_OPTION);
		private Options options;
		private Option option;
		CLIOptions(Options options, Option option) {
			this.options = options;
			this.option = option;
		}
		public Options getOptions() {
			return this.options;
		}
		public Option getOption() {
			return this.option;
		}
		public static CLIOptions parse(Option option) {
			for (CLIOptions opt : CLIOptions.values()) {
				if (opt.option != null &&
					opt.option.getOpt().equals(option.getOpt())) {
					return opt;
				}
			}
			return null;
		}
	}
	
	public static final Option CONFIG_OPTION = Option.builder()
			.desc("Path to config.json. ")
			.option("c")
			.longOpt("conf")
			.required()
			.hasArg()
			.build();
	
	public static final Option EXPORT_FIELD_OPTION = Option.builder()
			.desc("Export Checklist for Jira custom fields. ")
			.option("e")
			.longOpt("exportField")
			.required()
			.build();
	public static final Options EXPORT_FIELD_OPTIONS = new Options()
			.addOption(CONFIG_OPTION)
			.addOption(EXPORT_FIELD_OPTION);
	
	public static final Option FIELD_LIST_OPTION = Option.builder()
			.desc("Path of ChecklistField.json exported using exportField. ")
			.option("f")
			.longOpt("fieldList")
			.required()
			.hasArg()
			.build();

	public static final Option TRIGGER_EXPORT_OPTION = Option.builder()
			.desc("Trigger Checklist for Jira plugin to export checklist data. ")
			.option("t")
			.longOpt("triggerExport")
			.required()
			.build();
	public static final Option BYPASS_TRIGGER_OPTION = Option.builder()
			.desc("Bypass export trigger and instead supply a CSV of custom field ID to context ID")
			.option("b")
			.longOpt("bypassTrigger")
			.hasArg()
			.build();
	public static final Options TRIGGER_EXPORT_OPTIONS = new Options()
			.addOption(CONFIG_OPTION)
			.addOption(TRIGGER_EXPORT_OPTION)
			.addOption(FIELD_LIST_OPTION)
			.addOption(BYPASS_TRIGGER_OPTION);

	public static final Option WF_FILE_OPTION = Option.builder()
			.desc("File containing workflow information exported using workflowExport. ")
			.option("w")
			.longOpt("wf")
			.hasArg()
			.build();
	public static final Option GZ_DIR_OPTION = Option.builder()
			.desc("Folder containing .gz files exported using triggerExport. ")
			.option("g")
			.longOpt("gzDir")
			.required()
			.hasArg()
			.build();	
	public static final Option EXPORT_USAGE_OPTION = Option.builder()
			.desc(	"Export two CSV files that contain checklist templates and their association with projects. ")
			.option("u")
			.longOpt("exportUsage")
			.required()
			.build();
	public static final Options EXPORT_USAGE_OPTIONS = new Options()
			.addOption(CONFIG_OPTION)
			.addOption(EXPORT_USAGE_OPTION)
			.addOption(FIELD_LIST_OPTION)
			.addOption(GZ_DIR_OPTION)
			.addOption(WF_FILE_OPTION);

	public static final Option EXPORT_WORKFLOW_OPTION = Option.builder()
			.desc("Export workflows that may contain Checklist for Jira post-functions.")
			.option("ew")
			.longOpt("exportWorkflow")
			.required()
			.build();
	
	public static final Options EXPORT_WORKFLOW_OPTIONS = new Options()
			.addOption(CONFIG_OPTION)
			.addOption(EXPORT_WORKFLOW_OPTION);
	
	public static void printHelp() {
		HelpFormatter hf = new HelpFormatter();
		String command = "java -jar ChecklistForJira-[version].jar";
		System.out.println(command);
		hf.printHelp(command, EXPORT_WORKFLOW_OPTIONS, true);
		hf.printHelp(command, EXPORT_FIELD_OPTIONS, true);
		hf.printHelp(command, TRIGGER_EXPORT_OPTIONS, true);
		hf.printHelp(command, EXPORT_USAGE_OPTIONS, true);
	}
	
	public static CommandLine parseCommandLine(String[] args) {
		CommandLineParser parser = new DefaultParser();
		for (CLIOptions cli : CLIOptions.values()) {
			try {
				return parser.parse(cli.getOptions(), args);
			} catch (Exception ex) {
				// Ignore
			}
		}
		printHelp();
		return null;
	}
}
