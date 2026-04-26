# String Resource Migration Plan

## Objective
Migrate the custom `AppStrings` data class and `LocalAppStrings` mechanism to the Compose Multiplatform Resource (CMP Resource) system. This involves removing the custom localization logic, flattening the string hierarchy, and batch replacing imports and string access methods across the codebase using a Python script.

## Key Files & Context
- **Localization Files:** The `composeApp/src/commonMain/composeResources/files/i18n/*.json` files have been converted into `strings.xml` in their respective `values*` directories. Nested structures like `errors.errorDialogTitle` have been flattened to `errorDialogTitle` for CMP compatibility.
- **Affected Kotlin Files:** All `.kt` files within `commonMain`, `jvmMain`, and `androidMain` source sets that import or utilize `AppStrings`, `LocalAppStrings`, `getStrings()`, or `strings.key`.

## Implementation Steps

### 1. Execute the Migration Script
Save the following code as `migrate_strings.py` in the root of the project directory (`D:\AndroidStudioProjects\MicYou`), and run it via terminal: `python migrate_strings.py`.

```python
import os
import re

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    original_content = content

    # 1. Remove old imports
    content = re.sub(r'^import com\.lanrhyme\.micyou\.AppStrings\n?', '', content, flags=re.MULTILINE)
    content = re.sub(r'^import com\.lanrhyme\.micyou\.LocalAppStrings\n?', '', content, flags=re.MULTILINE)
    content = re.sub(r'^import com\.lanrhyme\.micyou\.getStrings\n?', '', content, flags=re.MULTILINE)

    needs_resources = False

    # 2. Remove LocalAppStrings declarations
    if re.search(r'val\s+strings\s*=\s*LocalAppStrings\.current', content):
        content = re.sub(r'\s*val\s+strings\s*=\s*LocalAppStrings\.current\n?', '', content)
        needs_resources = True
        
    # Remove getStrings() declarations
    if re.search(r'val\s+strings\s*=\s*getStrings\(.*?\)', content):
        content = re.sub(r'\s*val\s+strings\s*=\s*getStrings\(.*?\)\n?', '', content)
        needs_resources = True

    # Remove `strings: AppStrings` from parameters
    content = re.sub(r',\s*strings:\s*AppStrings', '', content)
    content = re.sub(r'strings:\s*AppStrings\s*,?\s*', '', content)

    # 3. Usage Replacement
    has_composable = '@Composable' in content
    func_name = 'stringResource' if has_composable else 'getString'

    # Handle `.replace("%s", arg)`
    content, count1 = re.subn(r'strings\.(?:[a-zA-Z0-9_]+\.)?([a-zA-Z0-9_]+)\.replace\("%s",\s*(.*?)\)', 
                              rf'{func_name}(Res.string.\1, \2)', content)
    
    # Handle `.format(arg)`
    content, count2 = re.subn(r'strings\.(?:[a-zA-Z0-9_]+\.)?([a-zA-Z0-9_]+)\.format\((.*?)\)', 
                              rf'{func_name}(Res.string.\1, \2)', content)

    # Handle direct access `strings.key` or `strings.nested.key`
    content, count3 = re.subn(r'strings\.(?:[a-zA-Z0-9_]+\.)?([a-zA-Z0-9_]+)', 
                              rf'{func_name}(Res.string.\1)', content)

    if count1 > 0 or count2 > 0 or count3 > 0:
        needs_resources = True

    # 4. Inject new imports
    if needs_resources:
        imports_to_add = [
            'import micyou.composeapp.generated.resources.*',
            'import micyou.composeapp.generated.resources.Res'
        ]
        if has_composable:
            imports_to_add.append('import org.jetbrains.compose.resources.stringResource')
        else:
            imports_to_add.append('import org.jetbrains.compose.resources.getString')
        
        # Insert after the last import
        last_import_match = list(re.finditer(r'^import .*\n', content, re.MULTILINE))
        if last_import_match:
            insert_pos = last_import_match[-1].end()
        else:
            pkg_match = re.search(r'^package .*?\n', content, re.MULTILINE)
            insert_pos = pkg_match.end() if pkg_match else 0
            
        imports_str = ''
        for imp in imports_to_add:
            if imp not in content:
                imports_str += imp + '\n'
                
        if imports_str:
            content = content[:insert_pos] + imports_str + content[insert_pos:]

    # 5. Handle Special Cases
    if 'App.kt' in filepath:
        # Remove CompositionLocalProvider wrapper
        content = re.sub(r'CompositionLocalProvider\(\s*LocalAppStrings provides strings,\s*LocalPermissionStrings provides strings\.permissions\s*\)\s*\{', '', content)

    if content != original_content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Updated {filepath}")

def main():
    dirs = [
        'composeApp/src/commonMain/kotlin',
        'composeApp/src/androidMain/kotlin',
        'composeApp/src/jvmMain/kotlin'
    ]
    for d in dirs:
        for root, _, files in os.walk(d):
            for file in files:
                if file.endswith('.kt'):
                    process_file(os.path.join(root, file))

if __name__ == '__main__':
    main()
```

### 2. Manual Adjustments Post-Script
- **`App.kt`**: The Python script removes the `CompositionLocalProvider` opening line, but you will need to manually remove the trailing closing brace `}` at the bottom of the function to prevent syntax errors. Also, remember to re-format the file indentation.
- **`MainViewModel.kt`**: You will need to manually clean up the `appStringProvider` reflection logic in the `init` block, as `MainViewModel` shouldn't require runtime reflection strings anymore with CMP Resources available natively.
- **Check Imports**: Ensure that IDE auto-import tools (like IntelliJ or Android Studio) don't flag any unresolved references, specifically for suspend functions utilizing `getString(Res.string.key)`. 

## Verification & Testing
1. Run `./gradlew build` to ensure the project compiles successfully.
2. Verify that strings are correctly accessed and UI renders localized strings.
3. Validate suspend functions (like in `VBCableManager`) are using `getString` correctly.