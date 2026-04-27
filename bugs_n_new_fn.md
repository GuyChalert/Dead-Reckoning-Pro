# Bugs
* steps and distance are STILL accumulating even when at rest while rotating the device: they shall be counted only when deevice is moving

* IGN SCAN25 and BRGM geologie are still not displayed when activated: dive deep into this! BRGM has WMS-C option (http://geoservices.brgm.fr/wms-c.html), WMS/WFS (http://geoservices.brgm.fr/geologie) and KML (https://infoterre.brgm.fr/sites/default/files/upload/kml/kml_geo_1000.kml, https://infoterre.brgm.fr/sites/default/files/upload/kml/kml_geo_50.kml, https://infoterre.brgm.fr/sites/default/files/upload/kml/scan_f_geol50_catalog.kmz, https://infoterre.brgm.fr/sites/default/files/upload/kml/risques.kmz) ; IGN has WMTS (BDTOPO: https://data.geopf.fr/wms-r?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities, ), WMS (RGEALTI: https://data.geopf.fr/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetCapabilities).

# New functionalities
* Add a north symbol to the map which rotates when map is rotated ; tapping on it restore default north up-screen config.

* Add support to load a ropography raster ; reproject in current CRS if needed ; add optional display of contoured topography on map view ; contour step default is 100m but can be modified in sertings ; when the map is zoomed, the step reduces successively to 50m, then 10m then 1m with increasing map zoom ; these contours are precomputed just after TIF import ; the contours resolution depends on map zoom -> several versions of the contours are precomputed at TIF import for quick display later.

7. Add a tab where a profile of elevation vs walked distance is displayed ; superpose elevation curve from topography is topo raster is available.

8. Barometer is activated by default but this can be xhanger in settings.