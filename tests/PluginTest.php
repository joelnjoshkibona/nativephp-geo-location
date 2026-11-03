<?php

/**
 * Plugin validation tests for GeoLocation.
 *
 * Run with: ./vendor/bin/pest
 */

beforeEach(function () {
    $this->pluginPath = dirname(__DIR__);
    $this->manifest = json_decode(file_get_contents($this->pluginPath.'/nativephp.json'), true);
});

describe('Plugin Manifest', function () {
    it('is valid json with the required fields', function () {
        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($this->manifest)->toHaveKeys(['name', 'namespace', 'bridge_functions']);
        expect($this->manifest['name'])->toBe('blutrixx/nativephp-geo-location');
        expect($this->manifest['namespace'])->toBe('GeoLocation');
    });

    it('names a Kotlin class for every bridge function', function () {
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/GeoLocationFunctions.kt');

        foreach ($this->manifest['bridge_functions'] as $function) {
            expect($function['name'])->toStartWith('GeoLocation.');
            $class = substr($function['android'], strrpos($function['android'], '.') + 1);
            expect($kotlin)->toContain("class {$class}(");
        }
    });

    it('calls only bridge functions that the manifest declares', function () {
        $php = file_get_contents($this->pluginPath.'/src/GeoLocation.php');
        preg_match_all("/'(GeoLocation\.[A-Za-z]+)'/", $php, $m);
        $declared = array_column($this->manifest['bridge_functions'], 'name');

        foreach (array_unique($m[1]) as $name) {
            expect($declared)->toContain($name);
        }
    });

    it('declares the foreground service with the location type, and its permissions', function () {
        $service = $this->manifest['android']['services'][0];
        expect($service['name'])->toBe('.GeoLocationService');
        expect($service['foregroundServiceType'])->toBe('location');
        expect($this->manifest['android']['permissions'])->toContain(
            'android.permission.FOREGROUND_SERVICE_LOCATION',
            'android.permission.ACCESS_BACKGROUND_LOCATION',
        );
        expect(file_exists($this->pluginPath.'/resources/android/GeoLocationService.kt'))->toBeTrue();
    });

    it('resumes recording after a restart (BOOT_COMPLETED filter on the boot receiver)', function () {
        $boot = $this->manifest['android']['receivers'][0];
        expect($boot['name'])->toBe('.GeoLocationBootReceiver');
        expect($boot['intent_filters'][0]['action'])->toContain('android.intent.action.BOOT_COMPLETED');
    });
});

describe('Kotlin sources', function () {
    it('all declare the plugin package', function () {
        foreach (glob($this->pluginPath.'/resources/android/*.kt') as $file) {
            expect(file_get_contents($file))->toContain('package com.blutrixx.plugins.geolocation');
        }
    });
});
