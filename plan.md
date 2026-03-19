1. **Add new prompt buttons to Prompt Gallery**
   - Add a "Janitor" prompt file (`janitor.md`) and a corresponding string resource.
   - Add an "Accessibility" prompt file (`accessibility.md`) and a corresponding string resource.
   - Update `activity_create_task.xml` to include `btnPromptJanitor` and `btnPromptAccessibility`.
   - Update `CreateTaskActivity.kt` to link these buttons to their respective prompt files.
   - *(Note: Initial additions for these default prompts have already been successfully completed and tested. I will verify this step and mark it complete.)*

2. **Implement "Custom" Prompt Button**
   - In `activity_create_task.xml`, add a `MaterialButton` for "Custom" (`btnPromptCustom`) inside the `FlexboxLayout`.
   - Style it to be visually distinct (e.g., using `Widget.Material3.Button` instead of `TonalButton`, or a different color).
   - Add a string resource for its title: "➕ Custom".

3. **Implement "Add Custom Prompt" Bottom Sheet/Dialog**
   - Create a new layout `dialog_add_custom_prompt.xml`.
   - Include fields for:
     - Title (`EditText` or `TextInputEditText`)
     - Emoji (can be part of the title or a separate field, but usually just one `EditText` for the combined title+emoji or two separate ones. The prompt says "A title", "An emoji", "The prompt body").
     - Prompt Body (`EditText` with multiple lines).
     - Save/Cancel buttons.
   - In `CreateTaskActivity.kt`, set a click listener on `btnPromptCustom` to show this dialog.

4. **Persist Custom Prompts and Dynamic Gallery Rendering**
   - We need a way to store custom prompts. `PreferenceUtils.kt` or a similar SharedPreferences manager can store a JSON list of custom prompts.
   - Create a data class `CustomPrompt(val id: String, val emoji: String, val title: String, val body: String)`.
   - In `CreateTaskActivity.kt`, when the gallery is loaded, dynamically inflate custom prompt buttons and add them to the `FlexboxLayout` alongside the default ones.
   - When a user saves a new custom prompt from the dialog, save it to preferences, then dynamically add the button to the layout.

5. **Manage Prompts Screen**
   - Create a new `ManagePromptsActivity.kt` and `activity_manage_prompts.xml`.
   - This screen needs a `RecyclerView` to list all prompts (defaults + custom).
   - The user can:
     - Toggle defaults on/off (needs persisting disabled states in SharedPreferences).
     - Reorder prompts (needs persisting order in SharedPreferences). Use `ItemTouchHelper` for drag-and-drop.
     - Edit/Delete custom prompts.
     - Add new custom prompts (button at the bottom or top).
   - Update `SettingsActivity.kt` to add a "Manage" button next to the Prompt Gallery switch, which launches `ManagePromptsActivity`.

6. **Long-press Editing on New Task Screen**
   - In `CreateTaskActivity.kt`, implement a long-press listener on prompt chips.
   - When long-pressed, enter "Edit Mode":
     - Chips start "wiggling" (a small rotation animation).
     - A small red 'X' icon appears on them (might require wrapping the buttons in a frame layout or using compound drawables).
     - Allow drag-to-reorder (might be tricky inside a FlexboxLayout compared to a RecyclerView, we may need to reconsider how the FlexboxLayout is constructed or use a specialized drag-and-drop mechanism for Flexbox).
     - When the red X is tapped, disable the prompt, hide it, and show a toast: "Prompt hidden. You can re-enable it in Settings."
   - *Consideration*: Drag-and-drop inside a `FlexboxLayout` can be complex. Alternatively, the Prompt Gallery could be migrated to a `RecyclerView` using `FlexboxLayoutManager`, which natively supports `ItemTouchHelper` for drag-and-drop! This is the standard way to do drag-and-drop flexbox grids in Android.

7. **Refactor Prompt Gallery to RecyclerView (Prerequisite for Drag & Drop)**
   - Replace the static `FlexboxLayout` in `activity_create_task.xml` with a `RecyclerView`.
   - Use `FlexboxLayoutManager` for the `RecyclerView`.
   - Create a `PromptAdapter` to handle both Default and Custom prompts.
   - Implement `ItemTouchHelper` in the adapter for drag-and-drop reordering.
   - Manage the wiggling state and 'X' visibility inside the adapter's `onBindViewHolder`.

8. **Testing and Verification**
   - Build and run the app.
   - Verify adding, editing, reordering, and deleting custom prompts.
   - Verify hiding default prompts.
   - Verify drag-and-drop in both `CreateTaskActivity` and `ManagePromptsActivity`.

9. **Pre-commit Steps**
   - Complete pre commit steps to ensure proper testing, verification, review, and reflection are done.

10. **Submit**
