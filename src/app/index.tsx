import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, NativeModules, AppState } from 'react-native';

const { ScrollMonitor } = NativeModules;

export default function Index() {
  const [stats, setStats] = useState({ screenTime: 0, punishments: 0 });
  const [permissions, setPermissions] = useState({ hasOverlay: false, hasAccessibility: false });

  const formatTime = (seconds: number) => {
    const h = Math.floor(seconds / 3600).toString().padStart(2, '0');
    const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0');
    const s = (seconds % 60).toString().padStart(2, '0');
    return `${h}:${m}:${s}`;
  };

  const syncData = async () => {
    try {
      const currentStats = await ScrollMonitor.getStats();
      const currentPerms = await ScrollMonitor.checkPermissions();
      setStats(currentStats);
      setPermissions(currentPerms);
    } catch (e) {
      console.error(e);
    }
  };

  useEffect(() => {
    // Initial sync
    syncData();

    // Poll data every second while app is open
    const interval = setInterval(syncData, 1000);
    
    // Refresh heavily when app comes back to foreground
    const subscription = AppState.addEventListener('change', nextAppState => {
      if (nextAppState === 'active') syncData();
    });

    return () => {
      clearInterval(interval);
      subscription.remove();
    };
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>DoomScroll Trap</Text>
      
      <View style={styles.card}>
        <Text style={styles.statLabel}>Screen Time Today</Text>
        <Text style={styles.statValue}>{formatTime(stats.screenTime)}</Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.statLabel}>Punishments Triggered</Text>
        <Text style={styles.statValue}>{stats.punishments}</Text>
      </View>

      {!permissions.hasAccessibility && (
        <TouchableOpacity 
          style={styles.button} 
          onPress={() => ScrollMonitor.openAccessibilitySettings()}
        >
          <Text style={styles.buttonText}>1. Enable Accessibility Service</Text>
        </TouchableOpacity>
      )}

      {!permissions.hasOverlay && (
        <TouchableOpacity 
          style={[styles.button, styles.secondaryButton]} 
          onPress={() => ScrollMonitor.requestOverlayPermission()}
        >
          <Text style={styles.buttonText}>2. Enable Overlay Permission</Text>
        </TouchableOpacity>
      )}

      {permissions.hasAccessibility && permissions.hasOverlay && (
        <Text style={styles.activeText}>Monitoring is Active ✓</Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
    alignItems: 'center',
    paddingTop: 80,
    paddingHorizontal: 20,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#fff',
    marginBottom: 40,
  },
  card: {
    backgroundColor: '#1E1E1E',
    width: '100%',
    padding: 20,
    borderRadius: 12,
    marginBottom: 20,
    alignItems: 'center',
  },
  statLabel: {
    color: '#888',
    fontSize: 16,
    marginBottom: 8,
  },
  statValue: {
    color: '#FF4444',
    fontSize: 32,
    fontWeight: 'bold',
  },
  button: {
    backgroundColor: '#4CAF50',
    width: '100%',
    padding: 16,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 10,
  },
  secondaryButton: {
    backgroundColor: '#2196F3',
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: 'bold',
  },
  activeText: {
    color: '#4CAF50',
    marginTop: 20,
    fontSize: 18,
    fontWeight: 'bold',
  }
});