# Independent AI Code Review — TEST 17

## Task
Review the uploaded Android Studio project and source code for unintended conflicts, data-loss risks, and regressions. Do not modify any files.

## Context
This release addresses a confirmed defect where HIDE LIST or RETRIEVE LIST could expose new-number Capture fields while an existing client remained selected. The required rule is: once an existing client is selected, Capture phone-entry fields and Capture SAVE/UPDATE must remain hidden; telephone maintenance belongs in Edit.

## Inspect specifically
1. HIDE LIST and RETRIEVE LIST state transitions when a client is selected.
2. Whether selectedContact can remain non-null while Capture fields or Capture SAVE become visible.
3. Capture SAVE/UPDATE data construction and whether it can overwrite existing phone numbers.
4. CLEAR, HIDE LIST, RETRIEVE LIST, and selection/deselection interactions.
5. Capture versus Edit separation, including Undo, Cancel, Return, and Save paths.
6. Google/Android Contacts synchronization and whether local B&S data can be silently overwritten.
7. Duplicate-number handling and the two required choices.
8. Empty telephone validation and preservation of blank local labelled rows.
9. Rotation, activity recreation, and premature app closure data retention.
10. Any Kotlin/Android lifecycle, state, nullability, or UI-thread risks.

## Required report format
- Executive summary
- Findings grouped by severity: Critical, High, Medium, Low, Informational
- For every finding: file/function, concrete code evidence, impact, and recommended correction
- Explicitly list areas inspected with no issue found
- Distinguish confirmed defects from possible risks
- State what cannot be verified without compiling or testing on a physical device
- Do not give an overall approval rating; provide evidence-based findings only
