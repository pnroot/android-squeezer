package com.danga.squeezer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class SqueezeService extends Service {
    private static final String TAG = "SqueezeService";
    private static final int PLAYBACKSERVICE_STATUS = 1;
	
    // Incremented once per new connection and given to the Thread
    // that's listening on the socket.  So if it dies and it's not the
    // most recent version, then it's expected.  Else it should notify
    // the server of the disconnection.
    private final AtomicInteger currentConnectionGeneration = new AtomicInteger(0);

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<ConnectionState>();
    
    private final AtomicReference<IServiceCallback> callback =
        new AtomicReference<IServiceCallback>();
  
    private boolean debugLogging = false;
    
    private WifiManager.WifiLock wifiLock;
    private SharedPreferences preferences;

    @Override
        public void onCreate() {
    	super.onCreate();
    	
        // Clear leftover notification in case this service previously got killed while playing                                                
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);
        
        wifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).createWifiLock(
                WifiManager.WIFI_MODE_FULL, "Squeezer_WifiLock");
        
        preferences = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);
        debugLogging = preferences.getBoolean(Preferences.KEY_DEBUG_LOGGING, false);
    }
	
    @Override
	public IBinder onBind(Intent intent) {
        return squeezeService;
    }
	
    @Override
	public void onDestroy() {
        super.onDestroy();
        disconnect();
        callback.set(null);
    }

    private void disconnect() {
        currentConnectionGeneration.incrementAndGet();
        ConnectionState connection = connectionState.get();
        if (connection != null) {
            connection.disconnect();
            connectionState.set(null);
        }
    }

    private synchronized void sendCommand(String... commands) {
        if (commands.length == 0) return;
        PrintWriter writer = socketWriter.get();
        if (writer == null) return;
        if (commands.length == 1) {
            Log.v(TAG, "SENDING: " + commands[0]);
            writer.println(commands[0]);
        } else {
            // Get it into one packet by deferring flushing...
            for (String command : commands) {
                writer.print(command + "\n");
            }
            writer.flush();
        }
    }
	
    private void sendPlayerCommand(String command) {
        ConnectionState connection = connectionState.get();
        if (connection != null) {
            connection.sendPlayerCommand(command);
        }
    }
	
    private void onLineReceived(String serverLine) {
        if (debugLogging) Log.v(TAG, "LINE: " + serverLine);
        List<String> tokens = Arrays.asList(serverLine.split(" "));
        if (tokens.size() < 2) {
            return;
        }
        if (serverLine.startsWith("players 0 100 count")) {
            parsePlayerList(tokens);
            return;
        }
        if ("pref".equals(tokens.get(0)) &&
            "httpport".equals(tokens.get(1)) &&
            tokens.size() >= 3) {
            httpPort.set(Integer.parseInt(tokens.get(2)));
            Log.v(TAG, "HTTP port is now: " + httpPort);
            return;
        }
        
        // Player-specific commands follow.  But we ignore all that aren't for our
        // active player.
        String activePlayer = activePlayerId();
        if (activePlayer == null || activePlayer.length() == 0 ||
            !decode(tokens.get(0)).equals(activePlayer)) {
            // Different player that we're not interested in.   
            // (yet? maybe later.)
            return;
        }
        String command = tokens.get(1);
        if (command == null) return;
        if (serverLine.contains("prefset server volume")) {
            String newVolume = tokens.get(4);
            Log.v(TAG, "New volume is: " + newVolume);
            sendNewVolumeCallback(Util.parseDecimalIntOrZero(newVolume));
            return;
        }
        if (command.equals("play")) {
            setPlayingState(true);
            return;
        }
        if (command.equals("stop")) {
            setPlayingState(false);
            return;
        }
        if (command.equals("pause")) {
            boolean newState = !isPlaying.get();
            if (tokens.size() >= 3) {
                String explicitPause = tokens.get(2); 
                if ("0".equals(explicitPause)) {
                    newState = true;  // playing.  (unpaused)
                } else if ("1".equals(explicitPause)) {
                    newState = false;  // explicitly paused.
                }
            }
            setPlayingState(newState);
            return;
        }
        if (command.equals("status")) {
            parseStatusLine(tokens);
            return;
        }
        if (command.equals("playlist")) {
            if (tokens.size() >= 4 && "newsong".equals(tokens.get(2))) {
                String newSong = decode(tokens.get(3));
                currentSong.set(newSong);
                updateOngoingNotification();
                sendMusicChangedCallback();
                
                // Now also ask for the rest of the status.
                sendPlayerCommand("status - 1 tags:ylqwaJ");
            }
        }

    }

    private String activePlayerId() {
        ConnectionState connection = connectionState.get();
        if (connection == null) return null;
        return connection.getActivePlayerId();
    }

    private void sendNewVolumeCallback(int newVolume) {
        if (callback.get() == null) return;
        try {
            callback.get().onVolumeChange(newVolume);
        } catch (RemoteException e) {
        }
    }

    private void sendNewTimeCallback(int secondsIn, int secondsTotal) {
        if (callback.get() == null) return;
        try {
            callback.get().onTimeInSongChange(secondsIn, secondsTotal);
        } catch (RemoteException e) {
        }
    }
    
    private void parseStatusLine(List<String> tokens) {
        int n = 0;
        boolean musicHasChanged = false;
        boolean sawArtworkId = false;
        int time = 0;
        int duration = 0;

        for (String token : tokens) {
            n++;
            if (n <= 2) continue;
            if (token == null || token.length() == 0) continue;
            int colonPos = token.indexOf("%3A");
            if (colonPos == -1) {
                if (n <= 4) continue;  // e.g. "00%3A04%3A20%3A05%3A09%3A36 status - 1 ...."
                Log.e(TAG, "Expected colon in status line token: " + token);
                return;
            }
            String key = decode(token.substring(0, colonPos));
            String value = decode(token.substring(colonPos + 3));
            if (key == null || value == null) continue;
            if (key.equals("mixer volume")) {
                continue;
            }
            if (key.equals("mode")) {
                if (value.equals("pause")) {
                    setPlayingState(false);
                } else if (value.equals("play")) {
                    setPlayingState(true);
                }
                continue;
            }
            if (key.equals("artist")) {
                if (Util.atomicStringUpdated(currentArtist, value)) musicHasChanged = true;
                continue;
            }
            if (key.equals("title")) {
                if (Util.atomicStringUpdated(currentSong, value)) musicHasChanged = true;
                continue;
            }
            if (key.equals("album")) {
                if (Util.atomicStringUpdated(currentAlbum, value)) musicHasChanged = true;
                continue;
            }
            if (key.equals("artwork_track_id")) {
                currentArtworkTrackId.set(value);
                sawArtworkId = true;
                continue;
            }
            if (key.equals("time")) {
                time = Util.parseDecimalIntOrZero(value);
                continue;
            }
            if (key.equals("duration")) {
                duration = Util.parseDecimalIntOrZero(value);
                continue;
            }
            // TODO: the rest ....
            // 00%3A04%3A20%3A17%3A04%3A7f status   player_name%3AOffice player_connected%3A1 player_ip%3A10.0.0.73%3A42648 power%3A1 signalstrength%3A0 mode%3Aplay time%3A99.803 rate%3A1 duration%3A224.705 can_seek%3A1 mixer%20volume%3A25 playlist%20repeat%3A0 playlist%20shuffle%3A0 playlist%20mode%3Adisabled playlist_cur_index%3A5 playlist_timestamp%3A1250053991.01067 playlist_tracks%3A46
        }
        if (musicHasChanged) {
            if (!sawArtworkId) {
                // TODO: we should disambiguate between no artwork because there is no
                // artwork (explicitly known) and no artwork because it's e.g. Pandora,
                // in which case we'd use the current cover.jpg URL.
                currentArtworkTrackId.set(null);
            }
            updateOngoingNotification();
            sendMusicChangedCallback();
        }
        Integer lastTimeInteger = currentTimeSecond.get();
        int lastTime = lastTimeInteger == null ? 0 : lastTimeInteger.intValue();
        if (musicHasChanged || time != lastTime) {
            currentTimeSecond.set(time);
            currentSongDuration.set(duration);
            sendNewTimeCallback(time, duration);
        }
    }
    
    private void parsePlayerList(List<String> tokens) {
        Log.v(TAG, "Parsing player list.");
        // TODO: can this block (sqlite lookup via binder call?)  Might want to move it elsewhere.
    	final String lastConnectedPlayer = preferences.getString(Preferences.KEY_LASTPLAYER, null);
    	Log.v(TAG, "lastConnectedPlayer was: " + lastConnectedPlayer);
        Map<String, String> players = new HashMap<String, String>();
                
        int n = 0;
        int currentPlayerIndex = -1;
        String currentPlayerId = null;
        String currentPlayerName = null;
        String defaultPlayerId = null;
        
        for (String token : tokens) {
            if (++n <= 3) continue;
            int colonPos = token.indexOf("%3A");
            if (colonPos == -1) {
                Log.e(TAG, "Expected colon in playerlist token.");
                return;
            }
            String key = token.substring(0, colonPos);
            String value = decode(token.substring(colonPos + 3));
            if (debugLogging) Log.v(TAG, "key=" + key + ", value: " + value);
            if ("playerindex".equals(key)) {
                maybeAddPlayerToMap(currentPlayerId, currentPlayerName, players);
                currentPlayerId = null;
                currentPlayerName = null;
                currentPlayerIndex = Integer.parseInt(value);
            } else if ("playerid".equals(key)) {
                currentPlayerId = value;
                if (value.equals(lastConnectedPlayer)) {
                    defaultPlayerId = value;  // Still around, so let's use it.
                }
            } else if ("name".equals(key)) {
                currentPlayerName = value;
            }
        }
        maybeAddPlayerToMap(currentPlayerId, currentPlayerName, players);

        if (defaultPlayerId == null || !players.containsKey(defaultPlayerId)) {
            defaultPlayerId = currentPlayerId;  // arbitrary; last one in list.
        }

        knownPlayers.set(players);
        
        if (callback.get() != null) {
            try {
                callback.get().onPlayersDiscovered();
            } catch (RemoteException e) {}
        }
        
        changeActivePlayer(defaultPlayerId);
    }

    private boolean changeActivePlayer(final String playerId) {
        ConnectionState connection = connectionState.get();
        if (connection == null) {
            return false;
        }
        return connection.changeActivePlayer(playerId);
    }
    
    private void updatePlayerSubscriptionState() {
        // Subscribe or unsubscribe to the player's realtime status updates
        // depending on whether we have an Activity or some sort of client
        // that cares about second-to-second updates.
        if (callback.get() != null) {
            sendPlayerCommand("status - 1 subscribe:1");
        } else {
            sendPlayerCommand("status - 1 subscribe:0");
        }
    }

    // Add String pair to map if both are non-null and non-empty.    
    private static void maybeAddPlayerToMap(String currentPlayerId,
            String currentPlayerName, Map<String, String> players) {
        if (currentPlayerId != null && !currentPlayerId.equals("") && 
            currentPlayerName != null && !currentPlayerName.equals("")) {
            Log.v(TAG, "Adding player: " + currentPlayerId + ", " + currentPlayerName);
            players.put(currentPlayerId, currentPlayerName);
        }
    }

    private String decode(String substring) {
        try {
            return URLDecoder.decode(substring, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private void onCliPortConnectionEstablished() {
        Thread listeningThread = new ListeningThread(socketRef.get(),
                                                     currentConnectionGeneration.incrementAndGet());
        listeningThread.start();

        sendCommand("listen 1",
                "players 0 100",   // get first 100 players
                "pref httpport ?"  // learn the HTTP port (needed for images)
        );
    }

    private void setConnectionState(boolean currentState, boolean postConnect) {
        isConnected.set(currentState);
        if (callback.get() == null) {
            return;
        }
        try {
            Log.d(TAG, "pre-call setting callback connection state to: " + currentState);
            callback.get().onConnectionChanged(currentState, postConnect);
            Log.d(TAG, "post-call setting callback connection state.");
        } catch (RemoteException e) {
        }
    }
	
    private void setPlayingState(boolean state) {
        // TODO: this might be running in the wrong thread.  Is wifiLock thread-safe?
        if (state && !wifiLock.isHeld()) {
            Log.v(TAG, "Locking wifi while playing.");
            wifiLock.acquire();
        }
        if (!state && wifiLock.isHeld()) {
            Log.v(TAG, "Unlocking wifi.");
            wifiLock.release();
        }
        
        isPlaying.set(state);
        updateOngoingNotification();
		
        if (callback.get() == null) {
            return;
        }
        try {
            callback.get().onPlayStatusChanged(state);
        } catch (RemoteException e) {
        }

    }

    private void updateOngoingNotification() {
        boolean playing = isPlaying.get();  
        if (!playing) {
            if (!preferences.getBoolean(Preferences.KEY_NOTIFY_OF_CONNECTION, false)) {
                clearOngoingNotification();
                return;
            }
        }
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification status = new Notification();
        //status.contentView = views;
        Intent showNowPlaying = new Intent(this, SqueezerActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, showNowPlaying, 0);
        String song = currentSong.get();
        if (song == null) song = "";
        if (playing) {
            status.setLatestEventInfo(this, "Music Playing", song, pIntent);
            status.flags |= Notification.FLAG_ONGOING_EVENT;
            status.icon = R.drawable.stat_notify_musicplayer;
        } else {
            status.setLatestEventInfo(this, "Squeezer's Connected", "No music is playing.", pIntent);
            status.flags |= Notification.FLAG_ONGOING_EVENT;
            status.icon = R.drawable.logo;
        }
        nm.notify(PLAYBACKSERVICE_STATUS, status);
    }

    private void sendMusicChangedCallback() {
        if (callback.get() == null) {
            return;
        }
        try {
            callback.get().onMusicChanged();
        } catch (RemoteException e) {
        }
    }

    private void clearOngoingNotification() {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);
    }

    private final ISqueezeService.Stub squeezeService = new ISqueezeService.Stub() {

        public void registerCallback(IServiceCallback callback) throws RemoteException {
            Log.v(TAG, "Callback attached.");
	    	SqueezeService.this.callback.set(callback);
	    	updatePlayerSubscriptionState();
	    }
	    
	    public void unregisterCallback(IServiceCallback callback) throws RemoteException {
            Log.v(TAG, "Callback detached.");
	    	SqueezeService.this.callback.compareAndSet(callback, null);
            updatePlayerSubscriptionState();
	    }

	    public int adjustVolumeBy(int delta) throws RemoteException {
	        if (isConnected()) {
	            return connectionState.get().adjustVolumeBy(delta);
	        }
        }

        public boolean isConnected() throws RemoteException {
            ConnectionState connection = connectionState.get();
            return connection == null ? false : connection.isConnected();
        }

        public void startConnect(final String hostPort) throws RemoteException {
            disconnect();  // if we're already connected to something
            int colonPos = hostPort.indexOf(":");
            boolean noPort = colonPos == -1;
            final int port = noPort? 9090 : Integer.parseInt(hostPort.substring(colonPos + 1));
            final String host = noPort ? hostPort : hostPort.substring(0, colonPos);
            
            ConnectionState newConnection = new ConnnectionState(
                    currentConnectionGeneration.incrementAndGet(),
                    host,
                    port);
            currentConnectionGeneration.set(newConnection);
            
            // Start the off-thread connect.
            executor.execute(new Runnable() {
                public void run() {
                    SqueezeService.this.disconnect();
                    Socket socket = new Socket();
                    try {
                        socket.connect(new InetSocketAddress(host, port),
                                       4000 /* ms timeout */);
                        socketRef.set(socket);
                        Log.d(TAG, "Connected to: " + hostPort);
                        socketWriter.set(new PrintWriter(socket.getOutputStream(), true));
                        Log.d(TAG, "writer == " + socketWriter.get());
                        setConnectionState(true, true);
                        Log.d(TAG, "connection state broadcasted true.");
                        onCliPortConnectionEstablished();
                    } catch (SocketTimeoutException e) {
                        Log.e(TAG, "Socket timeout connecting to: " + hostPort);
                        setConnectionState(false, true);
                    } catch (IOException e) {
                        Log.e(TAG, "IOException connecting to: " + hostPort);
                        setConnectionState(false, true);
                    }
                }

            });
        }

        public void disconnect() throws RemoteException {
            if (!isConnected()) return;
            SqueezeService.this.disconnect();
        }
		
        public boolean togglePausePlay() throws RemoteException {
            ConnectionState connection = connectionState.get();
            if (connection != null) {
                return connection.togglePlayPause();
            }
            return false;
        }

        public boolean play() throws RemoteException {
            ConnectionState connection = connectionState.get();
            if (connection != null) {
                return connection.play();
            }
            return false;
        }

        public boolean stop() throws RemoteException {
            ConnectionState connection = connectionState.get();
            if (connection != null) {
                return connection.stop();
            }
            return false;
        }

        public boolean nextTrack() throws RemoteException {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            sendPlayerCommand("button jump_fwd");
            return true;
        }
        
        public boolean previousTrack() throws RemoteException {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            sendPlayerCommand("button jump_rew");
            return true;
        }
        
        public boolean isPlaying() throws RemoteException {
            return isPlaying.get();
        }

        public boolean getPlayers(List<String> playerIds, List<String> playerNames)
            throws RemoteException {
            Map<String, String> players = knownPlayers.get();
            if (players == null) {
                return false;
            }
            for (String playerId : players.keySet()) {
                playerIds.add(playerId);
                playerNames.add(players.get(playerId));
            }
            return true;
        }

        public boolean setActivePlayer(String playerId) throws RemoteException {
            return changeActivePlayer(playerId);
        }

        public String getActivePlayerId() throws RemoteException {
            String playerId = activePlayerId.get();
            return playerId == null ? "" : playerId;
        }

        public String getActivePlayerName() throws RemoteException {
            String playerId = activePlayerId.get();
            Map<String, String> players = knownPlayers.get();
            if (players == null) {
                return null;
            }
            return players.get(playerId);
        }

        public String currentAlbum() throws RemoteException {
            return Util.nonNullString(currentAlbum);
        }

        public String currentArtist() throws RemoteException {
            return Util.nonNullString(currentArtist);
        }

        public String currentSong() throws RemoteException {
            return Util.nonNullString(currentSong);
        }

        public String currentAlbumArtUrl() throws RemoteException {
            Integer port = httpPort.get();
            if (port == null || port == 0) return "";
            String artworkTrackId = currentArtworkTrackId.get();
            if (artworkTrackId != null) {
                Log.v(TAG, "artwork track ID = " + artworkTrackId);
                return "http://" + currentHost.get() + ":" + port
                    + "/music/" + artworkTrackId + "/cover.jpg";
            } else {
                // Return the "current album art" URL instead, with the cache-buster
                // of the song name in it, to force the activity to reload when
                // listening to e.g. Pandora, where there is no artwork_track_id (tag J)
                // in the status.
                return "http://" + currentHost.get() + ":" + port
                    + "/music/current/cover?player=" + activePlayerId.get()
                    + "&song=" + URLEncoder.encode(currentSong());
            }
        }

        public int getSecondsElapsed() throws RemoteException {
            Integer seconds = currentTimeSecond.get();
            return seconds == null ? 0 : seconds.intValue();
        }

        public int getSecondsTotal() throws RemoteException {
            Integer seconds = currentSongDuration.get();
            return seconds == null ? 0 : seconds.intValue();
        }

        public void preferenceChanged(String key) throws RemoteException {
            Log.v(TAG, "Preference changed: " + key);
            if (Preferences.KEY_NOTIFY_OF_CONNECTION.equals(key)) {
                updateOngoingNotification();
                return;
            }
            if (Preferences.KEY_DEBUG_LOGGING.equals(key)) {
                debugLogging = preferences.getBoolean(key, false);
                return;
            }
        }
    };

    private class ListeningThread extends Thread {
        private final Socket socket;
        private final int generationNumber; 
        public ListeningThread(Socket socket, int generationNumber) {
            this.socket = socket;
            this.generationNumber = generationNumber;
        }
		
        @Override
            public void run() {
            BufferedReader in;
            try {
                in = new BufferedReader(
                                        new InputStreamReader(socket.getInputStream()),
                                        128);
            } catch (IOException e) {
                Log.v(TAG, "IOException while creating BufferedReader: " + e);
                SqueezeService.this.disconnect();
                return;
            }
            IOException exception = null;
            while (true) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException e) {
                    line = null;
                    exception = e;
                }
                if (line == null) {
                    // Socket disconnected.  This is expected
                    // if we're not the main connection generation anymore,
                    // else we should notify about it.
                    if (currentConnectionGeneration.get() == generationNumber) {
                        Log.v(TAG, "Server disconnected; exception=" + exception);
                        SqueezeService.this.disconnect();
                    } else {
                        // Who cares.
                        Log.v(TAG, "Old generation connection disconnected, as expected.");
                    }
                    return;
                }
                SqueezeService.this.onLineReceived(line);
            }
        }
    }
 }
