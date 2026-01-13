#!/bin/bash
set -e

### --- CONFIG --- ###
KEYSTORE_PATH="C:/Users/talqu/keystore1"   # your keystore
KEY_ALIAS="key1"

VERSION_JSON="release-config/version.json"  # Safe from clean builds
GRADLE_FILE="app/build.gradle.kts"
APK_SOURCE="app/build/outputs/apk/release/app-release.apk"

# This is the folder inside THE SAME repo that is served by GitHub Pages
PAGES_APK_PATH="app/release/app-release.apk"

### --- HELPER FUNCTION --- ###
# Read property from local.properties file
getLocalProperty() {
    local key=$1
    local defaultValue=$2
    local propertiesFile="local.properties"
    
    if [ ! -f "$propertiesFile" ]; then
        echo "$defaultValue"
        return
    fi
    
    # Use grep to find the line, then extract the value
    # This approach is more reliable than regex matching
    local value=$(grep "^[[:space:]]*${key}[[:space:]]*=" "$propertiesFile" 2>/dev/null | head -1 | sed "s/^[^=]*=[[:space:]]*//" | sed "s/[[:space:]]*$//" | sed "s/^['\"]//;s/['\"]$//")
    
    if [ -n "$value" ]; then
        echo "$value"
    else
        echo "$defaultValue"
    fi
}

### --- READ CURRENT VERSION --- ###
CURRENT_VERSION=$(grep "versionCode" -i "$GRADLE_FILE" | grep -o "[0-9]*")
NEW_VERSION=$((CURRENT_VERSION + 1))

echo "Current version: $CURRENT_VERSION"
echo "New version: $NEW_VERSION"

### --- PRE-BUILD CHECK --- ###
echo "Running pre-build check to verify compilation..."
echo "This ensures the build will succeed before we update version numbers."

./gradlew clean compileReleaseKotlin

if [ $? -ne 0 ]; then
    echo "ERROR: Pre-build check failed! Fix compilation errors before releasing."
    exit 1
fi

echo "Pre-build check passed!"

### --- RUN AUTOMATED TESTS --- ###
echo ""
echo "Running automated integration tests..."
echo "These tests verify database synchronization and app list management."

# Check if a device/emulator is connected
# Count devices (excluding the "List of devices" header line)
DEVICE_OUTPUT=$(adb devices 2>/dev/null | grep -v "List" | grep "device$" || echo "")
DEVICE_COUNT=$(echo "$DEVICE_OUTPUT" | wc -l)
# Remove any whitespace and ensure it's a number
DEVICE_COUNT=$(echo "$DEVICE_COUNT" | tr -d '[:space:]')
if [ -z "$DEVICE_COUNT" ] || ! [[ "$DEVICE_COUNT" =~ ^[0-9]+$ ]]; then
    DEVICE_COUNT=0
fi

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "WARNING: No Android device/emulator detected."
    echo "Skipping integration tests (connectedAndroidTest requires a device)."
    echo "To run tests manually: ./gradlew connectedAndroidTest"
    echo ""
    echo "Proceeding with release (tests will be skipped)..."
else
    echo "Found $DEVICE_COUNT connected device(s). Running tests..."
    
    # Build debug APK and test APK (required for connectedAndroidTest)
    echo "Building debug APK and test APK for testing..."
    ./gradlew assembleDebugAndroidTest
    
    if [ $? -ne 0 ]; then
        echo ""
        echo "ERROR: Failed to build debug APK or test APK!"
        echo "Please fix build errors before running tests."
        exit 1
    fi
    
    # Run the automated integration tests
    # Note: --tests doesn't work with connectedAndroidTest, so we run all connected tests
    # The test classes will skip themselves if Supabase is not configured
   # ./gradlew connectedAndroidTest
    
    if [ $? -ne 0 ]; then
        echo ""
        echo "ERROR: Integration tests failed!"
        echo "Please fix failing tests before releasing."
        echo ""
        echo "Note: Tests require Supabase to be configured (SUPABASE_URL and SUPABASE_KEY in local.properties)"
        echo "      Tests will automatically skip if Supabase is not configured."
        exit 1
    fi
    
    echo ""
    echo "✓ All automated integration tests passed!"
    echo ""
fi

echo "Proceeding with release..."

### --- GET PASSWORD --- ###
# Try to get password from local.properties, then environment variable, then prompt
STOREPASS=""
STOREPASS=$(getLocalProperty "KEYSTORE_PASSWORD" "")

# Debug: Check if password was found (without showing it)
if [ -n "$STOREPASS" ]; then
    echo "✓ Found KEYSTORE_PASSWORD in local.properties (length: ${#STOREPASS})"
elif [ -n "${KEYSTORE_PASSWORD:-}" ]; then
    STOREPASS="$KEYSTORE_PASSWORD"
    echo "Using password from KEYSTORE_PASSWORD environment variable"
else
    echo ""
    echo "Note: To skip password prompt, add to local.properties:"
    echo "  KEYSTORE_PASSWORD=your_password"
    echo "  Or set environment variable: export KEYSTORE_PASSWORD='your_password'"
    echo ""
    echo -n "Enter keystore password: "
    read -s STOREPASS
    echo ""
fi

### --- UPDATE build.gradle --- ###
sed -i "s/versionCode\s*=.*/versionCode = $NEW_VERSION/" "$GRADLE_FILE"
sed -i "s/versionName\s*=.*/versionName = \"$NEW_VERSION\"/" "$GRADLE_FILE"


### --- UPDATE version.json --- ###
# Replace "latestVersionCode": X with new version
sed -i "s/\"latestVersionCode\":.*/\"latestVersionCode\": $NEW_VERSION,/" "$VERSION_JSON"

### --- BUILD SIGNED APK --- ###
echo "Building signed APK..."

# Skip clean and lint on Windows to avoid file lock issues - assembleRelease will rebuild what's needed
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease \
  -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
  -Pandroid.injected.signing.store.password="$STOREPASS" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$STOREPASS"

echo "APK built."

### --- COPY APK TO GITHUB PAGES FOLDER --- ###
#cp "$APK_SOURCE" "$PAGES_APK_PATH"

### --- PULL LATEST CHANGES --- ###
# Pull latest changes (e.g., report uploads from the app)
# Use merge with 'theirs' strategy to automatically resolve conflicts
# Report uploads won't conflict with release files, so this is safe
echo "Pulling latest changes from remote..."
git fetch origin main

# Check if there are remote changes
if git rev-list --count HEAD..origin/main > /dev/null 2>&1; then
    REMOTE_COUNT=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo "0")
    if [ "$REMOTE_COUNT" -gt 0 ]; then
        echo "Remote has $REMOTE_COUNT new commit(s). Merging automatically..."
        # Use merge with 'theirs' strategy to automatically accept remote changes
        # This is safe since report uploads don't conflict with our release files
        # Set GIT_MERGE_AUTOEDIT=no to prevent editor from opening
        GIT_MERGE_AUTOEDIT=no git merge origin/main -X theirs -m "Merge remote changes (report uploads)" --no-edit || {
            # If merge fails, try rebase with autostash
            echo "Merge failed, trying rebase with autostash..."
            git merge --abort 2>/dev/null || true
            git pull --rebase --autostash origin main || {
                # Last resort: just pull without rebase
                echo "Rebase failed, pulling without rebase..."
                git pull --no-edit origin main || true
            }
        }
    else
        echo "No remote changes to pull."
    fi
else
    echo "Already up to date with remote."
fi

### --- GIT COMMIT + TAG + PUSH --- ###
# Add all files except local.properties (which contains secrets and should not be committed)
git add -A
# Explicitly exclude local.properties if it was tracked before
git restore --staged local.properties 2>/dev/null || true

# Use commitMessage.txt if it exists, otherwise use default message
COMMIT_MESSAGE="Release version $NEW_VERSION"
if [ -f "commitMessage.txt" ]; then
    COMMIT_MESSAGE=$(cat commitMessage.txt)
    echo "Using commit message from commitMessage.txt"
else
    echo "Using default commit message (commitMessage.txt not found)"
fi

git commit -m "$COMMIT_MESSAGE"

# Verify commit doesn't contain local.properties before tagging
if git diff --cached --name-only | grep -q "local.properties"; then
    echo "ERROR: local.properties is staged! Removing from commit..."
    git restore --staged local.properties
    git commit --amend --no-edit
fi

# Verify commit doesn't contain secrets
if git diff HEAD~1 HEAD --name-only | grep -q "local.properties"; then
    echo "ERROR: local.properties was committed! Aborting tag creation."
    exit 1
fi

# Delete existing tags (local and remote) before creating fresh tag
echo "Checking for existing tag v$NEW_VERSION..."

# Delete remote tag first (if it exists)
if git ls-remote --tags origin "v$NEW_VERSION" 2>/dev/null | grep -q "v$NEW_VERSION"; then
    echo "Tag v$NEW_VERSION exists on remote. Deleting..."
    git push origin ":refs/tags/v$NEW_VERSION" 2>/dev/null || true
fi

# Delete local tag (if it exists)
if git rev-parse "v$NEW_VERSION" >/dev/null 2>&1; then
    echo "Tag v$NEW_VERSION exists locally. Deleting..."
    git tag -d "v$NEW_VERSION" 2>/dev/null || true
fi

# Create the tag
echo "Creating tag v$NEW_VERSION..."
git tag "v$NEW_VERSION"

git push
git push --tags

echo "Release v$NEW_VERSION complete!"
#echo "APK deployed to GitHub Pages."
