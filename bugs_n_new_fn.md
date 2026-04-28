# Bugs

* app still again too sensitive to device manipulation and still counts steps while at rest: rotating the phone at rest is enough to trigger step and distance count ;  When walking, instantaneous acceleration is measured to above 4 to 5. Find another way to limit step counting while at rest! you may use capabilities of OnePlus 13 to do this.

* still an error with import of kmz (@provider/scan_f_geol50_catalog.kmz) -> screenshot in folder @provider/. It is displayed in the list of active layers but does not display anything on map.

* kml file @provider/kml_geo_50.kml still does not load the more detailed rasters when map is zoomed in. Plus it shows up above position symbol which is then hidden behind -> always move symbols (current position and markers) above all the active layers, inclusing when layers are loaded and unloaded. Last, some tiles are still visible after the layer has been hidden -> when inactivated, a layer shall not leave persistent pixels on the map.

* kmz file @scan_f_geol50_catalog.kmz does not load. Error is in screenshot in folder @/provider.

* north arrow: set a constant location for the north arrow ; make it rotate around its own center when the map rotates ; the last refactors did not change its wrong behavior

* Barometer shall be activated by default to assess elevation ; it shall be possible to turn it off and set elevation to constant user defined value in 'Calibrate' tab ; when barometer is ON, it shall be possible to calibrate it to a known elevation (user defined) in 'Calibrate' tab.



# New functionalities

* Add support to load a topography raster (RGEALTI from french IGN) on the fly within screen extent ; reproject in current CRS if needed ; display it as grey shades slopes ; add optional control to compute elevation contours: contour step default is 100m but can be modified in sertings ; when map is zoomed, step reduces successively by factor 2, then 5, then 10 with increasing map zoom level ; these contours are precomputed just after activating their display ; the contours resolution depends on map zoom -> several versions of the contours are precomputed for quick display.

* Add a tab where a profile of elevation vs walked distance is displayed ; superpose elevation curve from topography is topo raster is available.
