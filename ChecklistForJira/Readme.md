# Migration Tool for Checklist for Jira

## Purpose
Checklist for Jira is a plugin by HeroCoders. It adds checklists to Jira issues and allows workflows to interact with them.

However: 
1. Its integration with Jira Cloud Migration Assistant (JCMA) is limited to migrating data stored inside issues (to HeroCoders' servers). Only the Enterprise version provides the feature to import data from Jira Data Center/Server (you can use it during trial period). 
1. You will need to migrate the settings and checklist templates manually. 
1. Settings in Cloud version has changed greatly. 
1. The checklist template format has been changed in Cloud.

This tool provides the following functions:
1. Translate checklist templates from Data Center/Server to Cloud format.
1. List out actual checklist templates usage in projects. In Data Center/Server, the checklist templates are associated to custom field contexts (which tends to be applied to more projects/issue types than needed but limited by not adding the custom field to screens). In Cloud, the checklist templates are associated to projects instead. 
1. Trigger data export function in Checklist for Jira in Data Center/Server. That will result in a number of .gz files in Jira Data Center/Server for use by data import.

## Configuration
Modify config.json: 
1. ```"sourceDatabaseURL" : "jdbc:mysql://[Jira Database IP]:[Jira Database Port]/[Database Name]"```
1. ```"sourceDatabaseUser": "[Database User]"```
1. ```"sourceDatabasePassword": "[Database Password]"```
1. ```"sourceScheme": "https"```
1. ```"sourceHost": "[Jira Server IP]:[Jira Server Port]"```
1. ```"sourceUser": "[Jira User]"```
1. ```"sourcePassword": "[Jira Password]"```

## Usage
1. Workflows using Checklist for Jira validator/condition should be updated. 
    1. Condition is no longer supported. You must rewrite them as validator instead. 
    1. Validator no longer support JQL issue filter.
    1. Validator does not support working on specific checklist if the issue has multiple. 
1. Run this tool on a computer with network access to Jira server and database.
1. Execute command to generate ChecklistField.[Timesstamp].json, which contains the list of Checklist for Jira custom fields and projects they are used in: ```java -jar ChecklistForJira-[Version].jar -c config.json -e```
1. Execute command to trigger Checklist for Jira to start exporting data for migration. A number .gz files will be  generated in [Jira’s application data folder]/export/checklist. The export process will require some time to complete:   ```java -jar ChecklistForJira-[Version].jar -c config.json -t -f [ChecklistField.json]```
1. Execute command: ```java -jar ChecklistForJira-[Version].jar -c config.json -u -f [ChecklistField.json] -g [Jira’s Application Data folder/export/checklist]``` to generate: 
    1. ChecklistProject.[Timestamp].csv - This contains the projects that requires Checklist for Jira to be enabled.  
    1. ChecklistTemplate.[Timestamp].csv – This contains the content of checklist templates. 
    1. ChecklistUsage.[Timestamp].csv – This contains the projects and issue types the checklist templates are assigned to.
1. Create spreadsheet: 
    1. Open ChecklistUsage.[Timestamp].csv in Excel.  
    1. Save as ChecklistUsage.[Timestamp].xlsx.  
    1. In sheet ChecklistUsage.[Timestamp], sort the data using: 
        1. Project Key 
        1. Field Name 
        1. Template Name 
    1. In ribbon, choose Data | Get Data | From File | From Text/CSV. 
    1. Select ChecklistTemplate.[Timestamp].csv and click Import. 
    1. Click Transform Data. 
    1. In ribbon, click Use First Row as Headers. Then click Close & Load. 
    1. In ribbon, choose Data | Get Data | From File | From Text/CSV. 
    1. Select ChecklistProject.[Timestamp].csv and click Import. 
    1. In ribbon, click Use First Row as Headers. Then click Close & Load. 
    1. Save the xlsx file.  
1. After JCMA migration is compelete, use the spreadsheet to configure Checklist for Jira: 
    1. Global Settings 
        1. Login Jira Cloud as administrator. 
        1. Gear | Apps. 
        1. Click Manage apps in the left menu. 
        1. Search for “Checklist” and click Global settings under it. 
        1. Enable “Save local checklist items to Jira custom fields”. 
        1. Click “Enabled Projects” in the left menu. 
        1. Enable only the projects found in ChecklistProject.[Timestamp] sheet in ChecklistUsage.[Timestamp].xlsx. 
    1. Checklist Templates
        1. Refer to ChecklistTemplate.[Timestamp].xlsx to recreate the checklist templates.  
        1. Login Jira Cloud as administrator. 
        1. Click Projects | View all projects in the toolbar.  
        1. Select any project enabled in Global Settings.  
        1. View any issue in the project. (See notes below) 
        1. Scroll down the right panel to find Checklist. Click to open it. 
        1. Click 3-dots | Manage templates. 
        1. For each row in spreadsheet, where column Template Content is not empty:  
            1. Click Create template.  
            1. Click Switch to editor view.  
            1. Enter name from spreadsheet using columns Template Name.  The name must be unique and no longer than 50 characters.  
            1. By default, the template name chosen is [Custom field name]. If the custom field has multiple contexts, then context ID is appended as [Custom field name]:[Context ID].   
            1. The context name can be found in ChecklistUsage.[Timestamp] sheet’s Context Name column, but it is usually too long to be used as template name.  
            1. You can rename the template if desired, but remember the original name is used as a reference in ChecklistUsage.[Timestamp] sheet.  
            1. Paste the content from spreadsheet column Template Content into the editor. Remove leading and trailing double quotes.  
            1. Alternatively, select the cell, press F2, Ctrl-A, then Ctrl-C to copy the text without double quotes.  
            1. Some templates are too long for a single cell and will be split into additional columns. Combine the additional columns.  
            1. Click Save.  
        1. Filter ChecklistUsage.[Timestamp] sheet:  
            1. Set Template Empty column to only display FALSE. 
            1. For each row in ChecklistUsage.[Timestamp] sheet:  
            1. Projects | View all projects.  
            1. Search for the project key specified in Project Key column. 
            1. View Project settings. 
            1. Scroll down the right panel to find Checklist. Click to open it. 
            1. For each template name in Template Name(s) column:  
                1. Click Set default for the checklist template in Template Name column.  
                1. Select issue types according to the Issue Type(s) column.  
                1. Click Save.
            1. Note that you may encounter multiple rows for the same field. You can determine if the row is dummy by comparing project/issue type. This is caused by custom field context being applied to too many projects/issue types in Jira Data Center/Server. 
    1. Import data
        1. Install Checklist for Jira Enterprise. This can be installed at the same time with other versions of Checklist for Jira. 
        1. Gear | Apps.
        1. Click Manage apps in the left menu. 
        1. Scroll down left menu to locate “CHECKLIST FOR JIRA | ENTERPRISE”. 
        1. Click Import. 
        1. Click Checklist for Jira Server/DC. 
        1. Upload .gz files exported in Export Checklist for Jira Data in Jira Server. 
        1. Click Start import. 
        1. Wait for import to complete. Press F5 to reload the page to see status updates. It may take a few hours. 
        1. Note: the import process is known to get stuck if uploaded .gz files exceed a certain size. Contact HeroCoders support to have them fix it if the import got stuck.
            