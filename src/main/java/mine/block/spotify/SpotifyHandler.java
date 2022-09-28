package mine.block.spotify;

import com.github.winterreisender.webviewko.WebviewKo;
import com.sun.net.httpserver.HttpServer;
import mine.block.spoticraft.client.SpoticraftClient;
import mine.block.spotify.server.*;
import org.apache.hc.core5.http.ParseException;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static mine.block.spoticraft.client.SpoticraftClient.LOGGER;

public class SpotifyHandler {
    public static SpotifyApi SPOTIFY_API;
    public static SongChangeEvent songChangeEvent = null;

    public static void setup() {

        if(SpoticraftClient.CONFIG.empty) {
            LOGGER.info("No config present, starting oauth creation screen.");

            WebviewKo webview = new WebviewKo(1, null);
            webview.title("Spoticraft - Setup");
            webview.size(398, 677, WebviewKo.WindowHint.Fixed);

            try {
                final HttpServer server = HttpServer.create(new InetSocketAddress(23435), 0);
                server.createContext("/no-internet", new NoNetHandler());
                server.createContext("/setup", new SetupHandler());
                server.createContext("/setup-2", new SetupTwoHandler());
                server.createContext("/pre-callback", new PreCallbackHandler());
                server.createContext("/callback", new CallbackHandler(server));
                server.start();
            } catch (IOException e) {
                LOGGER.error("Failed to setup spotify.");
                throw new RuntimeException(e);
            }

            if(!SpotifyUtils.netIsAvailable()) {
                webview.url("http://localhost:23435/no-internet");
                webview.show();
                return;
            } else {
                webview.url("http://localhost:23435/setup");
                webview.show();
            }
        } else {
            SPOTIFY_API = new SpotifyApi.Builder()
                    .setClientId(SpoticraftClient.CONFIG.getProperty("client-id"))
                    .setClientSecret(SpoticraftClient.CONFIG.getProperty("client-secret"))
                    .setAccessToken(SpoticraftClient.CONFIG.getProperty("token"))
                    .setRefreshToken(SpoticraftClient.CONFIG.getProperty("refresh-token"))
                    .build();
            try {
                var creds = SPOTIFY_API.authorizationCodeRefresh().build().execute();

                SPOTIFY_API.setAccessToken(creds.getAccessToken());
                SPOTIFY_API.setRefreshToken(creds.getRefreshToken());

                SpoticraftClient.CONFIG.setProperty("token", creds.getAccessToken());
                SpoticraftClient.CONFIG.setProperty("refresh-token", creds.getRefreshToken());
                LOGGER.info("Refreshed Credentials.");
            } catch (IOException | SpotifyWebApiException | ParseException e) {
                LOGGER.error("Failed to setup spotify.");
                System.exit(1);
            }
        }

        try {
            SpoticraftClient.CONFIG.markDirty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        PollingThread thread = new PollingThread();
        executor.scheduleAtFixedRate(thread, 0, 5, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    public interface SongChangeEvent {
        void run(CurrentlyPlaying cp);
    }

    public static class PollingThread implements Runnable {
        private String song_id_old = "";

        @Override
        public void run() {
            try {
                var cp = SPOTIFY_API.getUsersCurrentlyPlayingTrack().build().execute();
                if (cp == null) return;
                if (!song_id_old.equals(cp.getItem().getId())) {
                    song_id_old = cp.getItem().getId();
                    if (songChangeEvent != null) {
                        songChangeEvent.run(cp);
                    }
                }
            } catch (IOException | SpotifyWebApiException | ParseException e) {
                LOGGER.warn("Failed to poll: " + e);
            }
        }
    }

}