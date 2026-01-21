#!/bin/bash
# Reward Timer Testing Script
# This script helps monitor the reward timer in real-time during testing

echo "=== BaerenLock Reward Timer Test Monitor ==="
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected!"
    echo "Please connect device and enable USB debugging"
    exit 1
fi

echo "✅ Device connected"
echo ""

# Function to query database
query_db() {
    adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db \\\"$1\\\"\""
}

# Function to display current state
show_state() {
    echo "=== Current State ($(date +%H:%M:%S)) ==="
    
    # Get banked_mins for both profiles
    echo ""
    echo "📊 Reward Time:"
    query_db "SELECT profile, banked_mins, datetime(banked_mins_timestamp, 'localtime') as last_update FROM user_data ORDER BY profile;"
    
    echo ""
    echo "👤 Active Profile:"
    query_db "SELECT active_profile FROM devices LIMIT 1;"
    
    echo ""
    echo "🎮 Reward Apps:"
    query_db "SELECT profile, reward_apps FROM user_data ORDER BY profile;"
    
    echo ""
    echo "================================================"
    echo ""
}

# Menu
echo "Choose test mode:"
echo "1) Monitor database continuously (updates every 5 seconds)"
echo "2) Show current state once"
echo "3) Grant reward time (test helper)"
echo "4) Watch logcat for timer events"
echo "5) Full test - Grant time and monitor"
read -p "Enter choice (1-5): " choice

case $choice in
    1)
        echo ""
        echo "Monitoring database (Ctrl+C to stop)..."
        echo ""
        while true; do
            clear
            show_state
            sleep 5
        done
        ;;
    2)
        show_state
        ;;
    3)
        read -p "Enter minutes to grant: " minutes
        echo "Granting $minutes minutes..."
        
        # Get active profile
        profile=$(query_db "SELECT active_profile FROM devices LIMIT 1;" | tr -d '\r\n')
        
        # Update banked_mins
        timestamp=$(date -u +"%Y-%m-%dT%H:%M:%S.000-05:00")
        query_db "UPDATE user_data SET banked_mins = $minutes, banked_mins_timestamp = '$timestamp' WHERE profile = '$profile';"
        
        echo "✅ Granted $minutes minutes to profile $profile"
        echo ""
        show_state
        ;;
    4)
        echo ""
        echo "Watching logcat for reward timer events (Ctrl+C to stop)..."
        echo "Looking for: timer checks, usage updates, expiration events"
        echo ""
        adb logcat -c  # Clear logcat
        adb logcat -s RewardManager:D AppBlocker:D | grep --line-buffered -E "performTimerCheck|actualUsage|updating from|Reward time expired|background reward timer"
        ;;
    5)
        echo ""
        read -p "Enter minutes to grant: " minutes
        read -p "Enter reward app package (e.g., com.netflix.mediaclient): " app
        
        # Get active profile
        profile=$(query_db "SELECT active_profile FROM devices LIMIT 1;" | tr -d '\r\n')
        
        echo ""
        echo "Setting up test..."
        echo "1. Adding $app as reward app for profile $profile"
        
        # Get current reward apps
        current_apps=$(query_db "SELECT reward_apps FROM user_data WHERE profile = '$profile';" | tr -d '\r\n')
        
        # Add app to reward apps if not already there
        if [[ ! "$current_apps" =~ "$app" ]]; then
            new_apps="$app"
            if [ -n "$current_apps" ]; then
                new_apps="$current_apps,$app"
            fi
            query_db "UPDATE user_data SET reward_apps = '$new_apps' WHERE profile = '$profile';"
            echo "   ✅ Added $app to reward apps"
        else
            echo "   ✅ $app already in reward apps"
        fi
        
        echo "2. Granting $minutes minutes reward time"
        timestamp=$(date -u +"%Y-%m-%dT%H:%M:%S.000-05:00")
        query_db "UPDATE user_data SET banked_mins = $minutes, banked_mins_timestamp = '$timestamp' WHERE profile = '$profile';"
        echo "   ✅ Granted $minutes minutes"
        
        echo ""
        echo "3. Initial state:"
        show_state
        
        echo ""
        echo "================================================"
        echo "TEST INSTRUCTIONS:"
        echo "================================================"
        echo "1. Open BaerenLock on device"
        echo "2. Verify $app appears in launcher"
        echo "3. Launch $app"
        echo "4. Keep $app in foreground"
        echo "5. Watch the banked_mins decrease below"
        echo ""
        echo "Expected: banked_mins decreases by 1 every minute"
        echo "          After $minutes minutes, app should be blocked"
        echo ""
        echo "Press Enter to start monitoring..."
        read
        
        clear
        echo "🔴 MONITORING STARTED - Keep reward app in foreground"
        echo ""
        
        start_time=$(date +%s)
        last_mins=$minutes
        
        while true; do
            current_time=$(date +%s)
            elapsed=$((current_time - start_time))
            elapsed_mins=$((elapsed / 60))
            
            clear
            echo "=== Test Monitor - Elapsed: ${elapsed_mins}m ${elapsed}s ==="
            
            # Get current banked_mins
            current_mins=$(query_db "SELECT banked_mins FROM user_data WHERE profile = '$profile';" | tr -d '\r\n')
            
            echo ""
            echo "⏱️  Time Elapsed: ${elapsed_mins} min ${elapsed} sec"
            echo "💰 Banked Mins: $current_mins (started with $minutes)"
            echo ""
            
            # Check if mins changed
            if [ "$current_mins" != "$last_mins" ]; then
                echo "✅ Timer updated! $last_mins → $current_mins"
                last_mins=$current_mins
            fi
            
            # Check if time expired
            if [ "$current_mins" == "0" ]; then
                echo ""
                echo "🎯 REWARD TIME EXPIRED!"
                echo "Expected: App should be blocked now"
                echo "Verify: Try to launch $app - it should be blocked immediately"
                echo ""
                show_state
                echo "Test complete! Press Ctrl+C to exit"
                sleep infinity
            fi
            
            echo ""
            echo "Full state:"
            query_db "SELECT profile, banked_mins, datetime(banked_mins_timestamp, 'localtime') as last_update FROM user_data WHERE profile = '$profile';"
            
            echo ""
            echo "Press Ctrl+C to stop monitoring"
            sleep 5
        done
        ;;
    *)
        echo "Invalid choice"
        exit 1
        ;;
esac
