// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.actions.downloadtasks;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Bounds.ParseMethod;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.gpx.GpxConstants;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.io.importexport.GpxImporter;
import org.openstreetmap.josm.gui.io.importexport.GpxImporter.GpxImporterData;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.markerlayer.MarkerLayer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressTaskId;
import org.openstreetmap.josm.gui.progress.ProgressTaskIds;
import org.openstreetmap.josm.io.BoundingBoxDownloader;
import org.openstreetmap.josm.io.OsmServerLocationReader;
import org.openstreetmap.josm.io.OsmServerReader;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.io.UrlPatterns.GpxUrlPattern;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Utils;
import org.xml.sax.SAXException;

/**
 * Task allowing to download GPS data.
 */
public class DownloadGpsTask extends AbstractDownloadTask<GpxData> {

    private DownloadTask downloadTask;
    private GpxLayer gpxLayer;

    protected String url;

    @Override
    public String[] getPatterns() {
        return patterns(GpxUrlPattern.class);
    }

    @Override
    public String getTitle() {
        return tr("Download GPS");
    }

    @Override
    public Future<?> download(DownloadParams settings, Bounds downloadArea, ProgressMonitor progressMonitor) {
        downloadTask = new DownloadTask(settings,
                new BoundingBoxDownloader(downloadArea), progressMonitor);
        // We need submit instead of execute so we can wait for it to finish and get the error
        // message if necessary. If no one calls getErrorMessage() it just behaves like execute.
        return MainApplication.worker.submit(downloadTask);
    }

    @Override
    public Future<?> loadUrl(DownloadParams settings, String url, ProgressMonitor progressMonitor) {
        this.url = Objects.requireNonNull(url);
        final Optional<String> mappedUrl = Stream.of(GpxUrlPattern.USER_TRACE_ID, GpxUrlPattern.EDIT_TRACE_ID)
                .map(p -> p.matcher(url))
                .filter(Matcher::matches)
                .map(m -> "https://www.openstreetmap.org/trace/" + m.group(2) + "/data")
                .findFirst();
        if (mappedUrl.isPresent()) {
            return loadUrl(settings, mappedUrl.get(), progressMonitor);
        }
        if (Stream.of(GpxUrlPattern.TRACE_ID, GpxUrlPattern.EXTERNAL_GPX_SCRIPT,
                      GpxUrlPattern.EXTERNAL_GPX_FILE, GpxUrlPattern.TASKING_MANAGER)
                .anyMatch(p -> p.matches(url))) {
            downloadTask = new DownloadTask(settings,
                    new OsmServerLocationReader(url), progressMonitor);
            // We need submit instead of execute so we can wait for it to finish and get the error
            // message if necessary. If no one calls getErrorMessage() it just behaves like execute.
            return MainApplication.worker.submit(downloadTask);

        } else if (GpxUrlPattern.TRACKPOINTS_BBOX.matches(url)) {
            String[] table = url.split("\\?|=|&", -1);
            for (int i = 0; i < table.length; i++) {
                if ("bbox".equals(table[i]) && i < table.length-1)
                    return download(settings, new Bounds(table[i+1], ",", ParseMethod.LEFT_BOTTOM_RIGHT_TOP), progressMonitor);
            }
        }
        return null;
    }

    @Override
    public void cancel() {
        if (downloadTask != null) {
            downloadTask.cancel();
        }
    }

    @Override
    public ProjectionBounds getDownloadProjectionBounds() {
        return gpxLayer != null ? gpxLayer.getViewProjectionBounds() : null;
    }

    class DownloadTask extends PleaseWaitRunnable {
        private final OsmServerReader reader;
        private GpxData rawData;
        private final boolean newLayer;

        DownloadTask(DownloadParams settings, OsmServerReader reader, ProgressMonitor progressMonitor) {
            super(tr("Downloading GPS data"), progressMonitor, false);
            this.reader = reader;
            this.newLayer = settings.isNewLayer();
        }

        @Override
        public void realRun() throws IOException, SAXException, OsmTransferException {
            try {
                if (isCanceled())
                    return;
                rawData = reader.parseRawGps(progressMonitor.createSubTaskMonitor(ProgressMonitor.ALL_TICKS, false));
            } catch (OsmTransferException e) {
                if (isCanceled())
                    return;
                rememberException(e);
            }
        }

        @Override
        protected void finish() {
            rememberDownloadedData(rawData);
            if (rawData == null)
                return;
            String name = getLayerName();

            GpxImporterData layers = GpxImporter.loadLayers(rawData, reader.isGpxParsedProperly(), name,
                    tr("Markers from {0}", name));

            gpxLayer = layers.getGpxLayer();
            addOrMergeLayer(gpxLayer, findGpxMergeLayer());
            addOrMergeLayer(layers.getMarkerLayer(), findMarkerMergeLayer(gpxLayer));

            layers.getPostLayerTask().run();
        }

        private String getLayerName() {
            // Extract .gpx filename from URL to set the new layer name
            final Matcher matcher = url != null ? GpxUrlPattern.EXTERNAL_GPX_FILE.matcher(url) : null;
            final String newLayerName = matcher != null && matcher.matches() ? matcher.group(1) : null;
            final String metadataName = rawData != null ? rawData.getString(GpxConstants.META_NAME) : null;
            final String defaultName = tr("Downloaded GPX Data");

            if (Config.getPref().getBoolean("gpx.prefermetadataname", false)) {
                return Utils.firstNotEmptyString(defaultName, metadataName, newLayerName);
            } else {
                return Utils.firstNotEmptyString(defaultName, newLayerName, metadataName);
            }
        }

        private <L extends Layer> L addOrMergeLayer(L layer, L mergeLayer) {
            if (layer == null) return null;
            if (newLayer || mergeLayer == null) {
                MainApplication.getLayerManager().addLayer(layer, zoomAfterDownload);
                return layer;
            } else {
                mergeLayer.mergeFrom(layer);
                mergeLayer.invalidate();
                return mergeLayer;
            }
        }

        private GpxLayer findGpxMergeLayer() {
            boolean merge = Config.getPref().getBoolean("download.gps.mergeWithLocal", false);
            Layer active = MainApplication.getLayerManager().getActiveLayer();
            if (active instanceof GpxLayer && (merge || ((GpxLayer) active).data.fromServer))
                return (GpxLayer) active;
            return MainApplication.getLayerManager().getLayersOfType(GpxLayer.class).stream()
                    .filter(l -> merge || l.data.fromServer)
                    .findFirst().orElse(null);
        }

        private MarkerLayer findMarkerMergeLayer(GpxLayer fromLayer) {
            return MainApplication.getLayerManager().getLayersOfType(MarkerLayer.class).stream()
                    .filter(l -> fromLayer != null && l.fromLayer == fromLayer)
                    .findFirst().orElse(null);
        }

        @Override
        protected void cancel() {
            setCanceled(true);
            if (reader != null) {
                reader.cancel();
            }
        }

        @Override
        public ProgressTaskId canRunInBackground() {
            return ProgressTaskIds.DOWNLOAD_GPS;
        }
    }

    @Override
    public String getConfirmationMessage(URL url) {
        // TODO
        return null;
    }

    @Override
    public boolean isSafeForRemotecontrolRequests() {
        return true;
    }
}
