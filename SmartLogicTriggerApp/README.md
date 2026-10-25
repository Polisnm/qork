# SmartLogicTriggerApp (WhatsApp to Excel Trigger App)

An Android Studio project (Kotlin) that listens to incoming WhatsApp & WhatsApp Business messages for trigger commands (`!add`, `!delete`, `!pending`, `!completed`, `!summary`) and automatically logs, updates, and deletes entries in a Microsoft Excel workbook (`SmartLogicTriggers.xlsx`) with headings:

**Date | Name | Complaint | Remark**

## Supported WhatsApp Commands

| Command | Example | Action on Excel Sheet (`Date`, `Name`, `Complaint`, `Remark`) |
| :--- | :--- | :--- |
| `!add <Name>, <Complaint>, <Remark>` | `!add Rahul, AC not cooling, Checked` | Appends a new row (`Date`, `Rahul`, `AC not cooling`, `Checked`) |
| `!pending <Name>, <Complaint>, <Remark>` | `!pending Neha, WiFi down, Part ordered` | Appends a new row or updates existing row with **Remark** = `Pending - Part ordered` |
| `!pending <ID or Name>` | `!pending 2` or `!pending Rahul` | Marks existing row `#2` or `Rahul` as `Pending` in the **Remark** column |
| `!pending` | `!pending` | Replies on WhatsApp with a list of all currently **Pending** rows |
| `!completed <ID or Name>, <Remark>` | `!completed Neha, Router replaced` | Updates matching row's **Remark** column to `Completed - Router replaced` |
| `!completed` | `!completed` | Replies on WhatsApp with a list of all **Completed** rows |
| `!delete <ID or Name>` | `!delete 2` or `!delete Rahul` | Deletes the matching row from the database and `.xlsx` sheet |
| `!delete all` | `!delete all` | Clears all rows from the Excel sheet |

## How to Build the APK in Android Studio

1. **Extract** `SmartLogicTriggerApp.zip` to a folder on your computer.
2. Open **Android Studio** and select **File > Open...**, then choose the extracted `SmartLogicTriggerApp` folder.
3. Wait for **Gradle Sync** to complete.
4. In the top menu bar, click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Once the build finishes, click **locate** in the bottom-right notification (or open `app/build/outputs/apk/debug/app-debug.apk`) and install the APK on your Android phone.
