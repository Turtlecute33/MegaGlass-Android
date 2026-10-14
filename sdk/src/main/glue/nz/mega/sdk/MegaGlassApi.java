package nz.mega.sdk;

/**
 * MegaApiJava with the Android graphics processor (thumbnails and previews for uploads).
 * Unlike MegaApiAndroid, listener callbacks run on the SDK thread and not on the main thread,
 * so the app can process them without blocking the UI.
 * Also exposes some MegaApi calls that MegaApiJava does not wrap.
 */
public class MegaGlassApi extends MegaApiJava {
    public MegaGlassApi(String appKey, String userAgent, String basePath) {
        super(appKey, userAgent, basePath, new AndroidGfxProcessor());
    }

    /** Transfer file data over HTTPS only. The data is end-to-end encrypted either way. */
    public void useHttpsOnly(boolean httpsOnly) {
        megaApi.useHttpsOnly(httpsOnly);
    }

    public boolean usingHttpsOnly() {
        return megaApi.usingHttpsOnly();
    }

    /** Parallel connections per transfer for one direction (MegaTransfer.TYPE_DOWNLOAD or TYPE_UPLOAD). */
    public void setMaxConnections(int direction, int connections) {
        megaApi.setMaxConnections(direction, connections);
    }
}
