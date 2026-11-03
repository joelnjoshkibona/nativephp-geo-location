<?php

namespace Blutrixx\GeoLocation;

use Illuminate\Support\ServiceProvider;

class GeoLocationServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__.'/../config/geo-location.php', 'geo-location');

        $this->app->singleton(GeoLocation::class, fn () => new GeoLocation());
        $this->app->alias(GeoLocation::class, 'nativephp.geo-location');
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->publishes([
                __DIR__.'/../config/geo-location.php' => config_path('geo-location.php'),
            ], 'geo-location-config');
        }
    }
}
