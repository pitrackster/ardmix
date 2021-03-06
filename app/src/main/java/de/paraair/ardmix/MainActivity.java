package de.paraair.ardmix;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.StrictMode;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.sciss.net.OSCClient;

import static de.paraair.ardmix.ArdourConstants.*;


public class MainActivity extends AppCompatActivity implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = "ArdourControl";

    private Context context;

    // application settings
    private String oscHost = "127.0.0.1";
    private int oscPort = 3819;
    private int bankSize = 8;
    private boolean useSendsLayout = false;
    private boolean useOSCbridge;

    private OscService oscService = null;


    // Ardour session values
    private Long maxFrame;
    private Long frameRate;
    private byte transportState = TRANSPORT_STOPPED;

    private BankLoadDialog dfBankLoad = null;

    // clock
    private TextView tvClock = null;

    // top level IO elements
    private ImageButton gotoStartButton = null;
    private ImageButton gotoEndButton = null;

    private final ToggleImageButton loopButton = null;
    private ToggleImageButton playButton = null;
    private ToggleImageButton stopButton = null;
    private ToggleImageButton recordButton = null;
    private Blinker blinker = null;

    private ToggleGroup transportToggleGroup = null;
    private SeekBar sbLocation;

    private LinearLayout llStripList;
    private final List<StripLayout> strips = new ArrayList<>();

    // some layouts for Sends, Receives, Panning, FX may be get more
    private int iAuxLayout = -1;
    private int iReceiveLayout = -1;
    private int iPanLayout = -1;
    private int iPluginLayout = -1;
    private int iSendsLayout = -1;

    private int selectStripId = -1;


    private PluginLayout pluginLayout;

    private final StripElementMask stripElementMask = new StripElementMask();

    private StripLayout masterStrip;
    private LinearLayout llMain;
    private HorizontalScrollView mainSroller;
    private LinearLayout llMaster;
    private LinearLayout llBankList;
    private SendsLayout sendsLayout;

    // xome elements for strip bankking
    private final ArrayList<Bank> banks = new ArrayList<>();
    private Bank selectBank;
    private Bank currentBank;
    private int bankId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        context = this;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // enable networking in main thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // restore preferences
        SharedPreferences settings = getSharedPreferences(TAG, 0);
        oscHost = settings.getString("oscHost", "127.0.0.1"); //if oscHost setting not found default to 127.0.0.1
        oscPort = settings.getInt("oscPort", 3819); //if oscPort setting not found default to 3819
        bankSize = settings.getInt("bankSize", 8);
        useSendsLayout = settings.getBoolean("useSendsLayout", false);
        useOSCbridge = settings.getBoolean("useOSCbridge", false);

        stripElementMask.loadSettings(settings);

        tvClock = (TextView) findViewById(R.id.str_clock);

        mainSroller = (HorizontalScrollView) findViewById(R.id.main_scoller);
        llStripList = (LinearLayout) findViewById(R.id.strip_list);
        llMain = (LinearLayout) findViewById(R.id.main_lauyout);
        llMaster = (LinearLayout) findViewById(R.id.master_view);

        //Create the transport button listeners
        gotoStartButton = (ImageButton) this.findViewById(R.id.bGotoStart);
        gotoStartButton.setOnClickListener(this);

        gotoEndButton = (ImageButton) this.findViewById(R.id.bGotoEnd);
        gotoEndButton.setOnClickListener(this);

        playButton = (ToggleImageButton) this.findViewById(R.id.bPlay);
        playButton.setOnClickListener(this);
        playButton.setAutoToggle(false);

        stopButton = (ToggleImageButton) this.findViewById(R.id.bStop);
        stopButton.setOnClickListener(this);
        stopButton.setAutoToggle(false);
        stopButton.toggle(); //Set stop to toggled state

        recordButton = (ToggleImageButton) this.findViewById(R.id.bRec);
        recordButton.setOnClickListener(this);
        recordButton.setAutoToggle(false);

        transportToggleGroup = new ToggleGroup();

        transportToggleGroup.addToGroup(playButton);
        transportToggleGroup.addToGroup(stopButton);
//        transportToggleGroup.addToGroup(loopButton);

//        sbLocation = (SeekBar) this.findViewById(R.id.locationBaR);
//        sbLocation.setMax(10000);
//        sbLocation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
//
//                @Override
//                public void onStopTrackingTouch(SeekBar arg0) {
//                }
//
//                @Override
//                public void onStartTrackingTouch(SeekBar arg0) {
//                }
//
//                @Override
//                public void onProgressChanged(SeekBar sb, int pos, boolean fromUser) {
//
//                    if (fromUser){
//
//                        if (!(RECORD_ENABLED == (transportState & RECORD_ENABLED)
//                        )){
//// && TRANSPORT_RUNNING == (transportState & TRANSPORT_RUNNING)
//
//                            int loc = Math.round(( sbLocation.getProgress() * maxFrame) / 10000);
//                            oscService.transportAction(OscService.LOCATE, loc, TRANSPORT_RUNNING == (transportState & TRANSPORT_RUNNING) );
//
//                            transportState = TRANSPORT_STOPPED;
//
//                            transportToggleGroup.toggle(stopButton, true);
//                            recordButton.toggleOff();
//                        }
//                    }
//                }
//            }
//        );
    }

    /**
     * Activity is has become visible
     * @see android.app.Activity#onResume()
     */
    @Override
    protected void onResume() {

        super.onResume();
        Log.d(TAG, "Resuming...");

        if (!oscService.isConnected()){
            Log.d(TAG, "Not connected to OSC server... Connect");
            this.startConnectionToArdour();
        }

        blinker = new Blinker();
        blinker.setHandler(topLevelHandler);
        blinker.addBlinker(recordButton);
        blinker.start();

    }

    @Override
    public void onStop(){
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        if( oscService == null ) {
            oscService = new OscService(oscHost, oscPort);
            oscService.setProtocol(useOSCbridge ? OSCClient.TCP : OSCClient.UDP);
            oscService.setTransportHandler(topLevelHandler);
        }
    }

    @Override
    protected void onDestroy() {
        stopConnectionToArdour();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch(id) {

            case R.id.action_connection:
                resetLayouts();
                SettingsDialogFragment dfSettings = new SettingsDialogFragment ();
                Bundle settingsBundle = new Bundle();
                settingsBundle.putString("host", oscHost);
                settingsBundle.putInt("port", oscPort);
                settingsBundle.putInt("bankSize", bankSize);
                settingsBundle.putBoolean("useSendsLayout", useSendsLayout);
                settingsBundle.putBoolean("useOSCbridge", useOSCbridge);
                dfSettings.setArguments(settingsBundle);
                dfSettings.show(getSupportFragmentManager(), "Connection Settings");
                break;
            case R.id.action_mask:
                stripElementMask.config(this);
                break;
            case R.id.action_connect:
                startConnectionToArdour();
                break;
            case R.id.action_disconnect:
                stopConnectionToArdour();
                break;

// Banking menu
            case R.id.action_newbank:
                newBank();
                break;
            case R.id.action_editbank:
                EditBank(banks.indexOf(currentBank));
                break;
            case R.id.action_removebank:
                RemoveBank(banks.indexOf(currentBank));
                break;
            case R.id.action_savebank:
                SaveBank(currentBank);
                break;
            case R.id.action_loadbank:
                LoadBank();
                break;

// Record button menu
            case R.id.action_allrecenable:
                oscService.transportAction(OscService.ALL_REC_ENABLE);
                break;

            case R.id.action_allrecdisable:
                oscService.transportAction(OscService.ALL_REC_DISABLE);
                break;

            case R.id.action_allrectoggle:
                oscService.transportAction(OscService.ALL_REC_TOGGLE);
                break;

// Strip In menu
            case R.id.action_allstripin_enable:
                oscService.transportAction(OscService.ALL_STRIPIN_ENABLE);
                break;

            case R.id.action_allstripin_disable:
                oscService.transportAction(OscService.ALL_STRIPIN_DISABLE);
                break;

            case R.id.action_allstripin_toggle:
                oscService.transportAction(OscService.ALL_STRIPIN_TOGGLE);
                break;

        }

        return super.onOptionsItemSelected(item);
    }

    private void newBank() {
        BankSettingDialogFragment dfBankSetting = new BankSettingDialogFragment ();
        Bundle bankBundle = new Bundle();
        bankBundle.putInt("bankIndex", -1);
        dfBankSetting.setArguments(bankBundle);
        dfBankSetting.show(getSupportFragmentManager(), "Bank Settings");
    }

    void LoadBank() {
        File dir = new File(context.getFilesDir().getPath());
        File[] files = dir.listFiles();

        HashMap<String,String> mapFileNames = new HashMap<>();
        for (File f: files
             ) {
            if (f.getName().endsWith(".bank")) {
                mapFileNames.put(f.getName(), f.getAbsolutePath());
                Log.d(TAG, "filename: " + f.getAbsolutePath());
            }
        }
        Bundle bankBundle = new Bundle();
        bankBundle.putStringArrayList("files", new ArrayList<>(mapFileNames.keySet()));
        dfBankLoad = new BankLoadDialog();
        bankBundle.putInt("bankIndex", -1);
        dfBankLoad.setArguments(bankBundle);
        dfBankLoad.show(getSupportFragmentManager(), "Load Bank");

    }

    private void SaveBank(Bank bank) {
        try {

            StringWriter stringWriter = new StringWriter();
            JsonWriter jsonWriter = new JsonWriter(stringWriter);
            jsonWriter.beginObject();
            jsonWriter.name("Bank").value(bank.getName());
            jsonWriter.name("Strips");
            jsonWriter.beginArray();
            for ( Bank.Strip strip : bank.getStrips() ) {

                jsonWriter.beginObject().name(strip.name).value(strip.id);
                jsonWriter.endObject();

            }
            jsonWriter.endArray();

            jsonWriter.endObject();
            jsonWriter.close();

            System.out.print(stringWriter.toString());
            FileOutputStream outputStream;
            String strFilename = bank.getName() + ".bank";
            outputStream = openFileOutput(strFilename, Context.MODE_PRIVATE);
            outputStream.write(stringWriter.toString().getBytes());
            outputStream.close();

        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    public void RemoveBank(int iBankIndex) {
        if( iBankIndex != 0 ) {
            final Bank _b = banks.get(iBankIndex);
            DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case DialogInterface.BUTTON_POSITIVE:
                            llBankList.removeView(_b.button);
                            banks.remove(_b);
                            showBank(0);
                            break;
                    }
                }
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setMessage("Are you sure to remove the current bank?")
                    .setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener)
                    .show();
        }
    }

    public void onSettingDlg(String host, int port, int bankSize, boolean useSendsLayout, boolean useOSCbridge) {
        this.oscHost = host;
        this.oscPort = port;
        this.bankSize = bankSize;
        this.useSendsLayout = useSendsLayout;
        this.useOSCbridge = useOSCbridge;
        savePreferences();
    }

    public void savePreferences(){

        SharedPreferences settings = getSharedPreferences(TAG, 0);
        SharedPreferences.Editor editor = settings.edit();

        editor.putString("oscHost", oscHost);
        editor.putInt("oscPort", oscPort);
        editor.putInt("bankSize", bankSize);
        editor.putBoolean("useSendsLayout", useSendsLayout);
        editor.putBoolean("useOSCbridge", useOSCbridge);

        editor.apply();
    }

    private void stopConnectionToArdour() {

        if (oscService.isConnected()){
            oscService.disconnect();
        }
        llStripList.removeAllViews();
        strips.clear();
        llMaster.removeView(masterStrip);
        if( llBankList != null)
            llBankList.removeAllViews();
        banks.clear();
    }

    private void startConnectionToArdour() {
        oscService.setHost(oscHost);
        oscService.setPort(oscPort);
        oscService.setProtocol(useOSCbridge ? OSCClient.TCP : OSCClient.UDP);

        stopConnectionToArdour();

        if( !oscService.connect() ) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setMessage("Failed to connect to ardour at " + oscHost)
                    .setPositiveButton("Ok", null)
                    .show();
        }


        oscService.requestStripList();
    }

    private StripLayout sl;
    private final Handler topLevelHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {

                case 5000: // perform blink
                    blinker.doBlink();
                    break;

                case StripLayout.STRIP_FADER_CHANGED:
                    Track _track = oscService.getTrack(msg.arg1 );

                    oscService.trackListAction(OscService.FADER_CHANGED, _track );
                    break;

                case SendsLayout.RESET_LAYOUT:
                    resetLayouts();
                    break;

                case SendsLayout.NEXT_SEND_LAYOUT:
                    int nl = currentBank.getStripPosition(iSendsLayout);
                    if( nl++ < currentBank.getStrips().size()-1 ) {
                        resetLayouts();
                        enableSendsLayout(currentBank.getStrips().get(nl).id, true);
                    }
                    break;

                case SendsLayout.PREV_SEND_LAYOUT:
                    int pl = currentBank.getStripPosition(iSendsLayout);
                    if( pl-- > 0) {
                        resetLayouts();
                        enableSendsLayout(currentBank.getStrips().get(pl).id, true);
                    }
                    break;

                case SendsLayout.SEND_CHANGED:
                    Track sendTrack =    oscService.getTrack(msg.arg1);
                    if (sendTrack != null)
                        oscService.trackSendAction(OscService.SEND_CHANGED, sendTrack, msg.arg2, (int)msg.obj );
                    break;

                case SendsLayout.SEND_ENABLED:
                    Track sendEnableTrack =    oscService.getTrack(msg.arg1);
                    if (sendEnableTrack != null)
                        oscService.trackSendAction(OscService.SEND_ENABLED, sendEnableTrack, msg.arg2, (boolean)msg.obj ? 1 : 0 );
                    break;

                case StripLayout.AUX_CHANGED:
                    Track auxTrack =    oscService.getTrack(msg.arg1);
                    if (auxTrack != null)
                        oscService.trackListAction(OscService.AUX_CHANGED, auxTrack );
                    break;

                case StripLayout.RECEIVE_CHANGED:
                    sl = getStripLayoutId(iAuxLayout);
                    if( sl != null ) {
                        Track receiveTrack = oscService.getTrack(sl.getRemoteId());
                        if (receiveTrack != null)
                            oscService.recvListVolumeAction(receiveTrack, msg.arg1, msg.arg2);
                    }
                    break;

                case StripLayout.PAN_CHANGED:
                    Track panTrack = oscService.getTrack(msg.arg1);
                    if (panTrack != null)
                        oscService.panAction( panTrack );
                    break;

                case PluginLayout.PLUGIN_PARAMETER_CHANGED:
                    Track pluginTrack = oscService.getTrack( msg.arg1 );
                    if( pluginTrack != null ) {
                        Object[] plargs = (Object[]) msg.obj;
                        oscService.pluginFaderAction(pluginTrack, msg.arg2, (int) plargs[0], (double) plargs[1]);
                    }
                    break;

                case PluginLayout.PLUGIN_DESCRIPTOR_REQUEST:
                    oscService.requestPlugin(msg.arg1, msg.arg2);
                    break;

                case PluginLayout.PLUGIN_BYPASS:
                    Track pluginTrack2 = oscService.getTrack( msg.arg1 );
                    if( pluginTrack2 != null ) {
                        oscService.pluginEnable(pluginTrack2, msg.arg2, (int) msg.obj == 1 );
                    }
                    break;

                case PluginLayout.PLUGIN_RESET:
                    Track pluginTrack1 = oscService.getTrack( msg.arg1 );
                    if( pluginTrack1 != null ) {
                        oscService.pluginAction(OscService.PLUGIN_RESET, pluginTrack1, msg.arg2 );
                        resetLayouts();
                    }
                    break;

                case PluginLayout.PLUGIN_NEXT:
                    int np = currentBank.getStripPosition(iPluginLayout);
                    if( np++ < currentBank.getStrips().size()-1 ) {
                        enablePluginLayout(currentBank.getStrips().get(np).id, true);
                    }
                    break;

                case PluginLayout.PLUGIN_PREV:
                    int pp = currentBank.getStripPosition(iPluginLayout);
                    if( pp-- > 0) {
                        enablePluginLayout(currentBank.getStrips().get(pp).id, true);
                    }
                    break;


                case OSC_FRAMERATE:
                    frameRate = (Long) msg.obj;
                    break;

                case OSC_MAXFRAMES:
                    maxFrame = (Long) msg.obj;
                    break;

                case OSC_STRIPLIST:
                    updateStripList();
                    oscService.initSurfaceFeedback2();
                    break;

                case OSC_RECONNECT:
                    startConnectionToArdour();
                    break;

                case OSC_NEWSTRIP:
                    addStrip((Track)msg.obj);
                    break;

                case OSC_STRIP_NAME:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.nameChanged();
                    break;

                case OSC_STRIP_REC:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.recChanged();
                    break;

                case OSC_STRIP_MUTE:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)

                        sl.muteChanged();
                    break;

                case OSC_STRIP_SOLO:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.soloChanged();
                    break;

                case OSC_STRIP_SOLOSAFE:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.soloSafeChanged();
                    break;

                case OSC_STRIP_SOLOISO:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.soloIsoChanged();
                    break;

                case OSC_STRIP_INPUT:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.inputChanged();
                    break;

                case OSC_STRIP_PAN:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null)
                        sl.panChanged();
                    break;

                case OSC_STRIP_FADER:
                    sl = getStripLayout(msg.arg1);
                    if (sl != null ) {
                        sl.getTrack().trackVolume = msg.arg2;
                        sl.volumeChanged();
                    }
                    break;

                case OSC_STRIP_RECEIVES:
                    Object args[] = (Object[]) msg.obj;

                    int remoteId = (int)args[0];
                    if (args.length % 5 == 0) {
                        for (int i = 0; i < args.length; i += 5) {
                            sl = getStripLayout((int) args[i]);
                            if (sl != null)
                                sl.setType(Track.TrackType.SEND, (Float) args[i + 3], (int) args[i + 2], (int) args[i + 4] == 1);
                        }
                    }
                    else {
                        if (iReceiveLayout == remoteId) {
                            for (int i = 1; i < args.length; i += 5) {
                                sl = getStripLayout((int) args[i]);
                                if (sl != null)
                                    sl.setType(Track.TrackType.SEND, (Float) args[i + 3], (int) args[i + 2], (int) args[i + 4] == 1);
                            }
                        }
                    }
                    break;

                case OSC_STRIP_SELECT:
                    if( msg.arg2  == 1 ) {
                        selectStripId = msg.arg1;
                    }
                    else {
                        selectStripId = -1;
                    }
                    break;

                // TODO: proprietary request should be removed
                case OSC_STRIP_SENDS:
                    Object sargs[] = (Object[]) msg.obj;

                    sl = getStripLayout(msg.arg1);

                    if (!useSendsLayout && iAuxLayout == msg.arg1) {
                        for (int i = 0; i < sargs.length; i += 5) {
                            if( (int)sargs[i] > 0 ) {
                                StripLayout receiveStrip = getStripLayout((int) sargs[i]);
                                if( receiveStrip != null)
                                    receiveStrip.setType(Track.TrackType.RECEIVE, (Float) sargs[i + 3], (int) sargs[i + 2], (int) sargs[i + 4] == 1);
                            }
                        }
                    }
                    else {
                        if( iAuxLayout == msg.arg1)
                            showSends(sl, sargs);
                    }
                    break;

                case OSC_SELECT_SEND_FADER:
                    sl = getStripLayoutId(iSendsLayout);
                    if( sendsLayout != null && sl != null && selectStripId == sl.getRemoteId()  ) {
                        Object sfargs[] = (Object[]) msg.obj;
                        sendsLayout.sendChanged((int)sfargs[0], (float) sfargs[1]);
                    }
                    break;

                case OSC_SELECT_SEND_ENABLE:
                    sl = getStripLayoutId(iSendsLayout);
                    if( sendsLayout != null && sl != null && selectStripId == sl.getRemoteId() ) {
                        Object sfargs[] = (Object[]) msg.obj;
                        sendsLayout.sendEnable((int)sfargs[0], (float) sfargs[1]);
                    }
                    break;

                case OSC_SELECT_SEND_NAME:
                    sl = getStripLayoutId(iSendsLayout);
                    if( sendsLayout != null && sl != null && selectStripId == sl.getRemoteId() ) {
                        String newName = (String) msg.obj;
                        if( !newName.equals(" ") )
                            sendsLayout.sendName(msg.arg1, (String) msg.obj);
                    }
                    break;


                case OSC_STRIP_METER:
                    sl = getStripLayout(msg.arg1);
                    if ( sl != null ) {
                        sl.meterChange();
                    }
                    break;

                // 0171/3532161

                case OSC_PLUGIN_LIST:
                    Object plargs[] = (Object[]) msg.obj;
                    Track track = oscService.getTrack((int)plargs[0]);
//                    track.pluginDescriptors.clear();
                    for( int pli = 1; pli < plargs.length; pli+=3 ) {
                        if( !track.pluginDescriptors.containsKey((Integer)plargs[pli]))
                            track.addPlugin((int)plargs[pli], (String)plargs[pli+1], (int)plargs[pli+2]);
                    }
                    showPluginLayout(track);
                    break;

                case OSC_PLUGIN_DESCRIPTOR:
                    Object pdargs[] = (Object[]) msg.obj;
                    int stripIndex = (int)pdargs[0];
                    int pluginId = (int)pdargs[1];
                    Track t = oscService.getTrack(stripIndex);

                    if( t != null ) {
                        ArdourPlugin pluginDes = t.getPluginDescriptor(pluginId);
//                        pluginDes.getParameters().clear();
//                        pluginDes.enabled = ((int)pdargs[2] == 1);
                        int pi = 2;
                        ArdourPlugin.InputParameter parameter;
                        if( (int) pdargs[2] > pluginDes.getParameters().size()) {
                            parameter = new ArdourPlugin.InputParameter((int) pdargs[pi], (String) pdargs[pi + 1]);
                        }
                        else {
                            parameter = pluginDes.getParameter((int) pdargs[2]);
                        }
                        parameter.flags = (int) pdargs[pi + 2];
                        parameter.type = (String) pdargs[pi + 3];
                        parameter.min = (float) pdargs[pi + 4];
                        parameter.max = (float) pdargs[pi + 5];
                        parameter.print_fmt = (String) pdargs[pi + 6];
                        parameter.scaleSize = (int) pdargs[pi + 7];
                        for (int spi = 0; spi < parameter.scaleSize; spi++) {
                            parameter.addScalePoint((float) pdargs[pi + 8], (String) pdargs[pi + 9]);
                            pi += 2;
                        }
                        parameter.current = (double) pdargs[pi + 8];
                        if( !pluginDes.getParameters().containsKey((Integer)pdargs[2])) {
                            pluginDes.addParameter((int) pdargs[2], parameter);
                        }
                        //showPlugin(pluginId, true);
                    }
                    break;
                case OSC_PLUGIN_DESCRIPTOR_END:
                    Object pdeargs[] = (Object[]) msg.obj;
                    Track pt = oscService.getTrack((int)pdeargs[0]);
                    showPlugin(pt, (int)pdeargs[1], true);
                    break;

                case OSC_UPDATE_CLOCK:
                    long clock = (long)msg.obj;

                    if( transportState == (transportState & RECORD_ENABLED)) {
                        maxFrame = clock;
                    }
//                    sbLocation.setProgress(Math.round(( (float) clock/ (float) maxFrame) * 10000));
//                    sbLocation.refreshDrawableState();
                    break;

                case OSC_UPDATE_CLOCKSTRING:
                    String strClock = (String)msg.obj;

                    tvClock.setText(strClock);
//                    sbLocation.setProgress(Math.round(( (float) clock/ (float) maxFrame) * 10000));
//                    sbLocation.refreshDrawableState();
                    break;

                case OSC_RECORD:
                    if( msg.arg1 == 1 ) {
                        transportState = (byte) (transportState | RECORD_ENABLED);
                        recordButton.setToggleState(true, true);
                    }
                    else {
                        transportState = (byte) (transportState ^ RECORD_ENABLED);
                        recordButton.setToggleState(false, false);
                    }
                    break;
                case OSC_PLAY:
                    if( msg.arg1 == 1 ) {
                        if (RECORD_ENABLED == (RECORD_ENABLED & transportState)) {
                            transportState = TRANSPORT_RUNNING | RECORD_ENABLED;
                            recordButton.toggleOn();
                        } else {
                            transportState = TRANSPORT_RUNNING;

                        }
                        transportToggleGroup.toggle(playButton, true);
                    }
                    else {
                        if (RECORD_ENABLED == (RECORD_ENABLED & transportState)) {
                            transportState = TRANSPORT_RUNNING ^ RECORD_ENABLED;
                            recordButton.toggleOff();
                        } else {
                            transportState ^= TRANSPORT_RUNNING;

                        }
                        transportToggleGroup.toggle(playButton, false);
                    }
                    break;

                case OSC_STOP:
                    if( msg.arg1 == 1 ) {
                        transportState = 0;
                    }
                    break;
            }
        }

    };

    public void addStrip(Track t) {

        final StripLayout stripLayout = new StripLayout(this, t);
        LinearLayout.LayoutParams stripLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);

        stripLayout.setPadding(0, 0, 0, 0);
        stripLayout.setLayoutParams(stripLP);
        stripLayout.setBackgroundColor(getResources().getColor(R.color.fader, null));

        stripLayout.setId(strips.size()+1);

        stripLayout.setOnClickListener(this);
        stripLayout.setOnChangeHandler(topLevelHandler);

        stripLayout.init(context, stripElementMask);

        if( t.type == Track.TrackType.MASTER ) {
            masterStrip = stripLayout;

        }
        strips.add(stripLayout);

        System.out.printf("adding strip %s with id %d\n", t.name, t.remoteId);

    }

    public void updateStripList() {

        if( banks.size() == 0) {
            createBanklist();
            updateBanklist();
            showBank(0);
        }

    }

    private void createBanklist() {

//        int iTrackInBank = 0;
//        int iBusBegin = 1;
//        int iBusEnd = 1;
//        Track.TrackType lt = Track.TrackType.AUDIO;

        for( Bank b: banks) {
            b.getStrips().clear();
        }

        banks.add (new Bank("All" ));

        Bank allBank = banks.get(0);
/*        Bank nb = new Bank();
        nb.setType(Bank.BankType.AUDIO);
        banks.add(nb);*/

        for( StripLayout sl: strips ) {
            Track t = sl.getTrack();

            if( t.type != Track.TrackType.MASTER ) {
                allBank.add(t.name, sl.getId(), true, t.type);

/*                if ( lt != t.type || iTrackInBank++ == bankSize) {
                    nb = new Bank();
                    banks.add(nb);
                    switch (t.type) {
                        case AUDIO:
                            nb.setType(Bank.BankType.AUDIO);
                            break;
                        case BUS:
                            nb.setType(Bank.BankType.BUS);
                            break;
                        case MIDI:
                            nb.setType(Bank.BankType.MIDI);
                            break;
                    }
                    iTrackInBank = 0;
                }

                nb.add(t.name, t.remoteId, true);
                lt = t.type;*/
            }
        }

/*        for( Bank b: banks) {
            switch(b.getType()) {
                case AUDIO:
                    b.setName(String.format("IN %d-%d", iBusEnd, iBusEnd + (b.getStrips().size())-1));
                    iBusEnd += b.getStrips().size() ;
                    break;
                case BUS:
                    b.setName(String.format("BUS %d-%d", iBusBegin, iBusBegin + (b.getStrips().size())-1));
                    iBusBegin += b.getStrips().size() - 1;
                    break;
            }
        }*/

    }

    private void updateBanklist() {

        bankId = 1000; //llBankList.generateViewId();
        llBankList = (LinearLayout) findViewById(R.id.bank_list);

        llBankList.removeAllViews();
        for( int iBankIndex = 0; iBankIndex < banks.size() ; iBankIndex++) {
            Bank _bank = banks.get(iBankIndex);
            _bank.button = new ToggleTextButton(this);
            LinearLayout.LayoutParams bankLP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    32);
            bankLP.setMargins(1,1,1,1);
//            bankLP.gravity = Gravity.RIGHT;
            _bank.button.setLayoutParams(bankLP);
            _bank.button.setPadding(0,0,0,0);
            _bank.button.setAllText(_bank.getName());
            _bank.button.setTag(iBankIndex);
            _bank.button.setId(bankId + iBankIndex + 1);
            _bank.button.setOnClickListener(this);
            _bank.button.setOnLongClickListener(this);
            _bank.button.setAutoToggle(true);
            _bank.button.setToggleState(false);
            llBankList.addView(_bank.button);
        }
        ToggleTextButton ttbAddBank = new ToggleTextButton(this);
        LinearLayout.LayoutParams bankLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                32);
        bankLP.setMargins(1,1,1,1);
        bankLP.gravity = Gravity.END;
        ttbAddBank.setLayoutParams(bankLP);
        ttbAddBank.setPadding(0,0,0,0);
        ttbAddBank.setAllText("+");
        ttbAddBank.setId(bankId);
        ttbAddBank.setOnClickListener(this);
        ttbAddBank.setAutoToggle(false);
        ttbAddBank.setToggleState(false);
        llBankList.addView(ttbAddBank);
        
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {

            case R.id.bGotoStart:
                if (transportState == TRANSPORT_RUNNING) {
                    transportToggleGroup.toggle(stopButton, true);
                    transportState = TRANSPORT_STOPPED;
                } else if (TRANSPORT_RUNNING == (transportState & TRANSPORT_RUNNING)
                        && RECORD_ENABLED == (transportState & RECORD_ENABLED)) {
                    break;
                }
                oscService.transportAction(OscService.GOTO_START);
                break;

            case R.id.bGotoEnd:
                if (transportState == TRANSPORT_RUNNING) {
                    transportToggleGroup.toggle(stopButton, true);
                    transportState = TRANSPORT_STOPPED;
                } else if (TRANSPORT_RUNNING == (transportState & TRANSPORT_RUNNING)
                        && RECORD_ENABLED == (transportState & RECORD_ENABLED)) {
                    break;
                }
                oscService.transportAction(OscService.GOTO_END);
                break;

            case R.id.bPlay:
                oscService.transportAction(OscService.TRANSPORT_PLAY);
                if (RECORD_ENABLED == (RECORD_ENABLED & transportState)) {
                    transportState = TRANSPORT_RUNNING | RECORD_ENABLED;
                    recordButton.toggleOn();
                } else {
                    transportState = TRANSPORT_RUNNING;

                }
                transportToggleGroup.toggle(playButton, true);
                break;

            case R.id.bStop:
                boolean wasRunning = (transportState == (TRANSPORT_RUNNING | RECORD_ENABLED));
                oscService.transportAction(OscService.TRANSPORT_STOP);

                transportState = TRANSPORT_STOPPED;
                transportToggleGroup.toggle(stopButton, true);
                if (wasRunning)
                    recordButton.toggleOff();
                break;

            case R.id.bRec:
                oscService.transportAction(OscService.REC_ENABLE_TOGGLE);
                if (RECORD_ENABLED != (transportState & RECORD_ENABLED)) {
                    if (TRANSPORT_STOPPED == (transportState & TRANSPORT_STOPPED)) {
                        recordButton.toggleOnAndBlink();
                    } else {
                        recordButton.toggleOn();
                    }
                    transportState = (byte) (transportState | RECORD_ENABLED);
                } else {
                    transportState = (byte) (transportState ^ RECORD_ENABLED);
                    recordButton.toggleOff();
                }
                break;

            case R.id.bLoopEnable:
                if (!(TRANSPORT_RUNNING == (transportState & TRANSPORT_RUNNING)
                        && RECORD_ENABLED == (transportState & RECORD_ENABLED))) {

                    oscService.transportAction(OscService.LOOP_ENABLE_TOGGLE);
                    transportToggleGroup.toggle(loopButton, true);
                }
                break;

            default:
                int i = v.getId() ;

                if (i - 1 >= bankId && i - 1 < bankId + banks.size() ) {
                    showBank((int)v.getTag() );
                    break;
                }

                if( i == bankId ) {
                    newBank();
                    break;
                }

                StripLayout sl = getStripLayoutId(i);
                switch ((String) v.getTag()) {
                    case "strip":
                        if( sl != null)
                            showStripDialog(sl.getRemoteId());
                        break;
                    case "rec":
                        if (sl != null && TRANSPORT_RUNNING != (transportState & TRANSPORT_RUNNING))
                            oscService.trackListAction(OscService.REC_CHANGED, oscService.getTrack(sl.getRemoteId()));
                        break;

                    case "mute":
                        if ( sl != null && sl.getShowType() == Track.TrackType.RECEIVE ) {
                            ToggleTextButton b = (ToggleTextButton)v;
                            StripLayout asl = getStripLayoutId(iAuxLayout);
                            if( asl != null )
                                oscService.setSendEnable(asl.getRemoteId(), sl.getTrack().source_id, b.getToggleState() ? 1f : 0f);
                        }
                        else if( sl != null && sl.getShowType() == Track.TrackType.SEND ) {
                            ToggleTextButton b = (ToggleTextButton)v;
                            oscService.setSendEnable(sl.getRemoteId(), oscService.getTrack(sl.getRemoteId()).source_id, b.getToggleState() ? 1f : 0f);
                        }
                        else if(  sl != null )
                            oscService.trackListAction(OscService.MUTE_CHANGED, oscService.getTrack(sl.getRemoteId()));
                        break;

                    case "solo":
                        if( sl != null )
                            oscService.trackListAction(OscService.SOLO_CHANGED, oscService.getTrack(sl.getRemoteId()));
                        break;

                    case "soloiso":
                        if( sl != null )
                            oscService.trackListAction(OscService.SOLO_ISOLATE_CHANGED, oscService.getTrack(sl.getRemoteId()));
                        break;

                    case "solosafe":
                        if( sl != null )
                            oscService.trackListAction(OscService.SOLO_SAFE_CHANGED, oscService.getTrack(sl.getRemoteId()));
                        break;

                    case "input":
                        if( sl != null )
                            oscService.trackListAction(OscService.STRIPIN_CHANGED, oscService.getTrack(sl.getRemoteId()));
                        break;

                    case "in":
                        if( sl != null )
                            enableSendOnFader(i, ((ToggleTextButton) v).getToggleState());
                        break;

                    case "aux":
                        if( !useSendsLayout )
                            enableReceiveOnFader(i,((ToggleTextButton) v).getToggleState());
                        else
                            enableSendsLayout(i, ((ToggleTextButton) v).getToggleState());
                        break;

                    case "pan":
                        enablePanFader(i, ((ToggleTextButton) v).getToggleState());
                        break;

                    case "fx":
                        enablePluginLayout(i, ((ToggleTextButton) v).getToggleState());
                        break;
                }
                break;
        }
    }

    @Override
    public boolean onLongClick(View v) {

        EditBank((int)v.getTag());
        return true;
    }

    private void enableSendsLayout(int iStripId, boolean bState) {
        StripLayout strip = getStripLayoutId(iStripId);
        if ( strip != null && bState) {

            resetLayouts();

            sendsLayout = new SendsLayout(this);
            sendsLayout.init(strip);
            sendsLayout.setOnChangeHandler(topLevelHandler);
            llStripList.addView(sendsLayout, strip.getPosition() + 1);

            strip.setBackgroundColor(getResources().getColor(R.color.SENDS_BACKGROUND, null));
            strip.pushVolume();
            iSendsLayout = iStripId;
            forceVisible(sendsLayout);

            oscService.selectStrip(strip.getRemoteId(), true);
        }
        else {
            if(!useSendsLayout) {
                for (StripLayout sl : strips) {
                    if (sl.showType == Track.TrackType.SEND || sl.showType == Track.TrackType.RECEIVE) {
                        sl.ResetType();
                        if (currentBank == null || !currentBank.contains(sl.getId() + 1))
                            llStripList.removeView(sl);
                    }
                }
            }
            else {
                if( sendsLayout != null ) {
                    sendsLayout.deinit();
                    llStripList.removeView(sendsLayout);
                    sendsLayout = null;
                }
            }
            if( strip != null) {
                strip.resetBackground();
                strip.sendChanged(false);
                strip.pullVolume();
            }
            iSendsLayout = -1;
        }
    }

    private void showSends(StripLayout strip, Object[] sargs) {
//        resetLayouts();
        sendsLayout = new SendsLayout(this);
//        sendsLayout.setRotation(90);
        sendsLayout.init(strip);
        sendsLayout.setOnChangeHandler(topLevelHandler);
        llStripList.addView(sendsLayout, strip.getPosition() + 1);
        forceVisible(sendsLayout);
    }

    private void enablePanFader(int iStripId, boolean bState) {
        StripLayout strip = getStripLayoutId(iStripId);

        if ( strip != null ) {
            if( bState) {

            resetLayouts();

            iPanLayout = iStripId;
            strip.setBackgroundColor(getResources().getColor(R.color.BUTTON_PAN, null));
            strip.setType(Track.TrackType.PAN, 0f, 0, false);
            strip.panChanged();

            }
            else {
                strip.ResetPan();
                strip.resetBackground();

                iPanLayout = -1;
                strip.volumeChanged();
            }
        }
    }

    private void enableSendOnFader(int iStripId, boolean bState) {
        StripLayout strip = getStripLayoutId(iStripId);
        if ( strip != null ) {
            if (bState) {
                resetLayouts();

                oscService.requestReceives(strip.getRemoteId());

                strip.setBackgroundColor(getResources().getColor(R.color.BUS_AUX_BACKGROUND, null));
                iReceiveLayout = iStripId;
            } else {
                for (StripLayout receiveLayout : strips) {
                    if (receiveLayout.showType == Track.TrackType.SEND || receiveLayout.showType == Track.TrackType.RECEIVE) {
                        receiveLayout.ResetType();
                        if (currentBank == null || !currentBank.contains(receiveLayout.getId()))
                            llStripList.removeView(receiveLayout);
                    }
                }
                strip.resetBackground();
                oscService.getTrack(strip.getRemoteId()).recEnabled = false;
                strip.recChanged();
                iReceiveLayout = -1;
            }
        }
    }

    private void enableReceiveOnFader(int iStripId, boolean bState) {
        StripLayout strip = getStripLayoutId(iStripId);
        if ( strip != null ) {
            if (bState) {

                resetLayouts();
                oscService.requestSends(strip.getRemoteId());

                strip.setBackgroundColor(getResources().getColor(R.color.BUS_AUX_BACKGROUND, null));
                strip.pushVolume();
                iAuxLayout = iStripId;
            } else {
                for (StripLayout sl : strips) {
                    if (sl.showType == Track.TrackType.SEND || sl.showType == Track.TrackType.RECEIVE) {
                        sl.ResetType();
                        if (currentBank == null || !currentBank.contains(sl.getId()))
                            llStripList.removeView(sl);
                    }
                }
                strip.resetBackground();
                strip.sendChanged(false);
                strip.pullVolume();
                iAuxLayout = -1;
            }
        }
    }

    private void enablePluginLayout(int iStripId, boolean bState) {
        StripLayout strip = getStripLayoutId(iStripId);
        if ( strip != null ) {
            if (bState) {

                resetLayouts();
                pluginLayout = new PluginLayout(this);
                pluginLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
                pluginLayout.setOrientation(LinearLayout.VERTICAL);
                pluginLayout.setBackgroundColor(getResources().getColor(R.color.PLUGIN_BACKGROUND, null));
                pluginLayout.setPadding(1, 0, 1, 0);
//            pluginLayout.setId(iStripId);
                pluginLayout.setOnChangeHandler(topLevelHandler);
                //place layout in strip list
                if (strip.getRemoteId() == oscService.getMasterId()) // its the master strip
                    llStripList.addView(pluginLayout);
                else
                    llStripList.addView(pluginLayout, strip.getPosition() + 1);


                iPluginLayout = iStripId;
                // we are ready to receive plugin list
                oscService.requestPluginList(strip.getRemoteId());
                strip.setBackgroundColor(getResources().getColor(R.color.PLUGIN_BACKGROUND, null));
            } else {
                if (pluginLayout != null) {
                    pluginLayout.removeAllViews();
                    llStripList.removeView(pluginLayout);
                }
                strip.fxOff();
                strip.resetBackground();

                pluginLayout = null;
                iPluginLayout = -1;
            }
        }
    }

    private void showPluginLayout(Track track) {
        if( pluginLayout != null ) {
            if(pluginLayout.track == null || pluginLayout.track.remoteId == track.remoteId ) {
                pluginLayout.initLayout(true, track);
                if (track.pluginDescriptors.size() == 0)
                    forceVisible(pluginLayout);
            }
        }
    }

    private void forceVisible(final View v) {
        v.post(new Runnable() {
            @Override
            public void run() {
                Rect rectScrollBounderies = new Rect();
                mainSroller.getDrawingRect(rectScrollBounderies);
                int sl = mainSroller.getScrollX();
                if( v.getRight() - rectScrollBounderies.right > 0  )
                    mainSroller.smoothScrollTo(v.getRight() - rectScrollBounderies.right + sl, 0);
            }
        });
    }

    private void showPlugin(Track t, int pluginId, boolean bState) {
        if( bState ) {
            if( pluginLayout == null )
                resetLayouts();
            else {
                if(pluginLayout.track.remoteId == t.remoteId) {
                    pluginLayout.init(pluginId);
//                pluginLayout.setTag(pluginDes);
                    forceVisible(pluginLayout);
                }
            }
        }
        else {
            if( pluginLayout != null ) {
                StripLayout sl = getStripLayout(pluginLayout.getId());
                if( sl != null )
                    sl.fxOff();
                llStripList.removeView(pluginLayout);
                pluginLayout = null;
            }
        }
    }

    private void resetLayouts() {

        if( iAuxLayout != -1  )
            enableReceiveOnFader(iAuxLayout, false);

        if( iReceiveLayout != -1  )
            enableSendOnFader(iReceiveLayout, false);

        if( iPanLayout != -1  )
            enablePanFader(iPanLayout, false);

        if( iPluginLayout != -1 )
            enablePluginLayout(iPluginLayout, false);

        if( iSendsLayout != -1 )
            enableSendsLayout(iSendsLayout, false);

    }

    private void showStripDialog(int iStripIndex) {
        Track t = oscService.getTrack(iStripIndex);
        StripSettingDialogFragment dfStripSetting = new StripSettingDialogFragment ();
        Bundle settingsBundle = new Bundle();
        settingsBundle.putString("stripName", t.name);
        settingsBundle.putInt("stripIndex", iStripIndex);
        if(t.type == Track.TrackType.AUDIO) {
            settingsBundle.putBoolean("stripIn", t.stripIn);
            settingsBundle.putBoolean("stripRecord", t.recEnabled);
        }
        settingsBundle.putBoolean("stripMute", t.muteEnabled);
        if(t.type != Track.TrackType.MASTER) {
            settingsBundle.putBoolean("stripSolo", t.soloEnabled);
            settingsBundle.putBoolean("stripSoloIso", t.soloIsolateEnabled);
            settingsBundle.putBoolean("stripSoloSafe", t.soloSafeEnabled);
        }

        dfStripSetting.setArguments(settingsBundle);
        dfStripSetting.show(getSupportFragmentManager(), "Strip Settings");

    }

    private StripLayout getStripLayout(int iRemoteid) {
        for( StripLayout sl: strips) {
            if( sl.getRemoteId() == iRemoteid )
                return sl;
        }
        return null;
    }

    private StripLayout getStripLayoutId(int id) {
        for( StripLayout sl: strips) {
            if( sl.getId() == id )
                return sl;
        }
        return null;
    }

    public void onStripDlg(int iStripIndex, String strName, boolean bStripIn, boolean bRecord, boolean bMute, boolean bSolo, boolean bSoloIso, boolean bSoloSafe) {
        Track t = oscService.getTrack(iStripIndex);
        t.name = strName;
        oscService.trackListAction(OscService.NAME_CHANGED, oscService.getTrack(iStripIndex));
        if(t.muteEnabled != bMute)
            oscService.trackListAction(OscService.MUTE_CHANGED, oscService.getTrack(iStripIndex));
        if( t.type != Track.TrackType.MASTER) {
            if (t.soloEnabled != bSolo)
                oscService.trackListAction(OscService.SOLO_CHANGED, oscService.getTrack(iStripIndex));
            if (t.soloIsolateEnabled != bSoloIso)
                oscService.trackListAction(OscService.SOLO_ISOLATE_CHANGED, oscService.getTrack(iStripIndex));
            if (t.soloSafeEnabled != bSoloSafe)
               oscService.trackListAction(OscService.SOLO_SAFE_CHANGED, oscService.getTrack(iStripIndex));
            if( t.type != Track.TrackType.BUS) {
                if(t.stripIn != bStripIn)
                    oscService.trackListAction(OscService.STRIPIN_CHANGED, oscService.getTrack(iStripIndex));
                if(t.recEnabled != bRecord)
                    oscService.trackListAction(OscService.REC_CHANGED, oscService.getTrack(iStripIndex));
            }
        }
    }

    public void onBankDlg(int iBankIndex, Bank bank) {
        if (selectBank != null && iBankIndex != -1) {
            selectBank.setName(bank.getName());
            selectBank.getStrips().clear();
            for( Bank.Strip strip: bank.getStrips()) {
                StripLayout sl = getStripLayout(strip.id);
                if( sl != null )
                    selectBank.add(strip.name, sl.getId(), strip.enabled, strip.type);
            }
            showBank((int)selectBank.getButton().getTag());
        }
        else {
            for( Bank.Strip strip: bank.getStrips()) {
                StripLayout sl = getStripLayout(strip.id);
                if( sl != null )
                    strip.id =  sl.getId();
            }
            banks.add(bank);
            updateBanklist();
            showBank((int)bank.getButton().getTag());
        }
    }

    private void EditBank(int iBankIndex) {
        selectBank = banks.get(iBankIndex);
        BankSettingDialogFragment dlg = new BankSettingDialogFragment ();
        Bundle settingsBundle = new Bundle();
        settingsBundle.putString("bankName", selectBank.getName());
        settingsBundle.putInt("bankIndex", iBankIndex);
        dlg.setArguments(settingsBundle);
        dlg.show(getSupportFragmentManager(), "Bank Settings");
    }

    public Bank getBank(int iBankIndex) {
        Bank result;
        result = new Bank("new bank");
        if (iBankIndex != -1) {
            Bank clone = banks.get(iBankIndex);
            result.setName(clone.getName());
            for( Bank.Strip s: clone.getStrips()) {
                StripLayout sl = getStripLayout(s.id);
                if( sl != null )
                    result.add(s.name, sl.getRemoteId(), false, s.type);
            }
            result.button = clone.getButton();
        }
        return result;
    }

    private void showBank(int iBankIndex) {
        resetLayouts();
        ToggleTextButton _bankButton;

        Bank _bank = banks.get(iBankIndex);
        llStripList.removeAllViews();

        for( int i = 0; i < banks.size(); i++ ) {
            banks.get(i).getButton().setToggleState(false);
        }

        if( stripElementMask.stripSize == StripLayout.AUTO_STRIP) {
            stripElementMask.autoSize = llMain.getWidth() / (_bank.getStripCount() + 1);
        }

        int c = 0;
        for( Bank.Strip bankStrip: _bank.getStrips()) {
            StripLayout sl = getStripLayoutId(bankStrip.id);
            if( sl != null) {
                sl.init(context, stripElementMask);
                llStripList.addView(sl, c);
               sl.setPosition(c++);
            }
        }

        llMaster.removeAllViews();
        if( masterStrip != null ) {
            masterStrip.init(context, stripElementMask);
            llMaster.addView(masterStrip);
        }


        _bankButton = (ToggleTextButton) llBankList.getChildAt(iBankIndex);
        _bankButton.toggleOn();
        currentBank = _bank;
    }

    public HashMap<Integer, Track> getRoutes() {
        return oscService.getRoutes();
    }

    public void LoadBankFile(Object tag) {
        if( dfBankLoad != null ) {
            dfBankLoad.dismiss();
            Bank bank = new Bank();
            int nBytesRead;
            try {
                FileInputStream inputStream;
                StringBuilder content = new StringBuilder();
                byte[] buffer = new byte[1024];
                inputStream = openFileInput((String) tag);
                while ((nBytesRead = inputStream.read(buffer)) != -1)
                {
                    content.append(new String(buffer, 0, nBytesRead));
                }
                inputStream.close();
                Log.d(TAG, "load file content: " + content.toString());

                StringReader stringReader = new StringReader(content.toString());
                JsonReader reader = new JsonReader(stringReader);
                reader.beginObject();
                if( reader.nextName().equals("Bank"))
                    bank.setName(reader.nextString());
                if( reader.nextName().equals("Strips")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginObject();
                        bank.add(reader.nextName(), reader.nextInt(), true, Track.TrackType.AUDIO);
                        reader.endObject();
                    }
                    reader.endArray();
                }
                reader.endObject();
                banks.add(bank);
                updateBanklist();

            } catch (IOException e) {
                e.printStackTrace();
            }
            showBank(banks.indexOf(bank));
        }
        dfBankLoad = null;
    }

    public void onStripMaskDlg() {

        showBank((int)currentBank.getButton().getTag());

        SharedPreferences settings = getSharedPreferences(TAG, 0);
        SharedPreferences.Editor editor = settings.edit();

        stripElementMask.saveSettings(editor);


        editor.commit();
    }

    public void getProcessors(int iStripIndex) {
        oscService.getProcessors(iStripIndex );
    }
}
