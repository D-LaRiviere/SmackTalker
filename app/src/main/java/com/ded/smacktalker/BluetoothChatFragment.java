package com.ded.smacktalker;

import android.app.ActionBar;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.security.SecureRandom;

/**
 * This fragment controls Bluetooth to communicate with other devices.
 *
 * The base code is taken from the open-source Google Samples code and
 * heavily changed to hook into our app. See README.md
 */
public class BluetoothChatFragment extends Fragment {

    private static final String TAG = "BluetoothChatFragment";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    // Layout Views
    private ListView myListView;
    private EditText newMessageText;
    private Button mSendButton;

    private static String userID;
    private static String deviceID;
    private static final String USERIDKEY = "userID";
    private static final String DEVICEKEY = "deviceID";

    private myDBHandler dbHandler;

    /**
     * Stores what table in the database is to be accessed
     */
    protected String currentTable = myDBHandler.TABLE_MESSAGES;

    //Used for randomly generated userIDs
    private static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static SecureRandom rnd;

    //Preferences which carry across run time sessions
    SharedPreferences prefs;
    SharedPreferences.Editor editor;
    //Will contain all messages currently unread
    StringBuilder unread;
    //Will store whether the app is in the fore or background
    private static boolean inBack = false;
    //Will store if device is in panic mode
    static boolean panicMode = false;

    Menu menu;


    /**
     * Name of the connected device
     */
    private String mConnectedDeviceName = null;

    /**
     * String buffer for outgoing messages
     */
    private StringBuffer mOutStringBuffer;

    /**
     * Local Bluetooth adapter
     */
    private BluetoothAdapter mBluetoothAdapter = null;

    /**
     * Member object for the chat services
     */
    private BluetoothChatService mChatService = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        //Since the last three parameters are constants of the class, null is passed.
        dbHandler = new myDBHandler(getContext());
        prefs = getActivity().getPreferences(Context.MODE_PRIVATE);
        editor = prefs.edit();
        unread = new StringBuilder();
        rnd = new SecureRandom();

        //Get deviceID from preferences
        deviceID = prefs.getString(DEVICEKEY, null);

        if(deviceID == null){
            //One in 57 billion chance of two users having the same deviceID with this method
            //Create randomized deviceID
            int len = 6;
            StringBuilder sb = new StringBuilder(len);
            //Random # created which points to a string of all possible chars.
            //Appends that char onto the userID and repeats until desired length.
            for( int i = 0; i < len; i++ ) {
                sb.append(AB.charAt(rnd.nextInt(AB.length())));
            }
            deviceID = sb.toString();

            //Stores the userID under the key specified in the final USERIDKEY
            editor.putString(DEVICEKEY, deviceID);
            editor.apply();
        }

        //Null is the default value. If no userID is saved, the default value assigned will be null.
        userID = prefs.getString(USERIDKEY, null);

        if(userID == null){
            //UserID has not been set
            setUserID();
        }



        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            FragmentActivity activity = getActivity();
            Toast.makeText(activity,R.string.bt_unavailable, Toast.LENGTH_LONG).show();
            activity.finish();
        }
        showHelp();
    }


    /**
     * Requests to turn on bluetooth and sets up chat service.
     */
    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else if (mChatService == null) {
            setupChat();
        }
    }

    /**
     * Stops BluetoothChatService
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            mChatService.stop();
        }
    }

    /**
     * Clears notifications and unread messages. Restarts BluetoothChatService if necessary.
     */
    @Override
    public void onResume() {
        super.onResume();

        //When the app is resumed from background, assume all messages are read.
        //Clear the unread messages stored in the stringbuilder.
        unread.delete(0, unread.length());

        //App is now in the foreground, not the back.
        inBack = false;

        //Clear any unread message notifications
        deleteNotifications();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
        populateListView();
    }

    /**
     * Tracks that app is now in the background for unread messages tracking.
     */
    @Override
    public void onPause() {
        super.onPause();
        //Set bool saying app is running in background
        inBack = true;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        myListView = (ListView) view.findViewById(R.id.listView);
        newMessageText = (EditText) view.findViewById(R.id.newMessageText);
        mSendButton = (Button) view.findViewById(R.id.sendButton);
        Log.d(TAG, "Views created");
    }

    /**
     * Set up the UI and background operations for chat.
     */
    private void setupChat() {
        Log.d(TAG, "setupChat()");

        /*// Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<String>(getActivity(), R.layout.message);
        myListView.setAdapter(mConversationArrayAdapter);*/

        myListView.setOnItemClickListener(
                new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        //Get the string value of the view that was touched at position # (which is stored in position
                        //This WILL enable copy to clipboard

                        //Get cursor from myListView
                        SQLiteCursor c  = (SQLiteCursor) parent.getItemAtPosition(position);
                        //Move cursor position to the corresponding item touched
                        c.moveToPosition(position);
                        //Get the Message from the MESSAGETEXT column in the database
                        String message = c.getString(c.getColumnIndex(myDBHandler.COLUMN_MESSAGE_TEXT));

                        //Save message to clipboard
                        //Get handle for clipboard service
                        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                        //Create text clip
                        ClipData clip = ClipData.newPlainText(TAG, message);
                        //Add text clip to clipboard
                        clipboard.setPrimaryClip(clip);

                        Toast.makeText(getContext(), R.string.clipboard_copy, Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // Initialize the compose field with a listener for the return key
        newMessageText.setOnEditorActionListener(mWriteListener);

        // Initialize the send button with a listener that for click events
        mSendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                View view = getView();
                if (null != view) {
                    if(!newMessageText.getText().toString().equals("")) {
                        //Only run if newMessageText is not empty

                        String message = newMessageText.getText().toString();
                        //Add to database a new MessageData object with fields.
                        dbHandler.addMessage(currentTable, new MessageData(message, userID));

                        //Clear text field
                        newMessageText.setText("");

                        //Refresh myListView
                        populateListView();

                        //Convert object to byte[]
                        byte[] send = convertToBytes(new MessageData(message, userID));

                        sendMessage(send);
                    }else {
                        Toast.makeText(getContext(), R.string.editText_empty, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new BluetoothChatService(getActivity(), mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    /**
     * Makes this device discoverable.
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    /**
     * Sends a message.
     *
     * @param bytes The byte array to be sent
     */
    private void sendMessage(byte[] bytes) {
        boolean condition = false;
        // Check that we're actually connected before trying anything
        try{
            condition = mChatService.getState() != BluetoothChatService.STATE_CONNECTED;
        }catch (NullPointerException e){
            Toast.makeText(getContext(), R.string.error_try_again, Toast.LENGTH_SHORT).show();
            setupChat();
        }
        if (condition) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        mChatService.write(bytes);

        // Reset out string buffer to zero and clear the edit text field
        mOutStringBuffer.setLength(0);
        newMessageText.setText(mOutStringBuffer);

    }

    /**
     * The action listener for the EditText widget, to listen for the return key
     */
    private TextView.OnEditorActionListener mWriteListener
            = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            // If the action is a key-up event on the return key, send the message
            if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                if(!newMessageText.getText().toString().equals("")) {
                    String message = newMessageText.getText().toString();
                    //Add to database a new MessageData object with fields.
                    dbHandler.addMessage(currentTable, new MessageData(message, userID));

                    //Clear text field
                    newMessageText.setText("");

                    //Refresh myListView
                    populateListView();

                    //Convert object to byte[]
                    byte[] send = convertToBytes(new MessageData(message, userID));

                    sendMessage(send);
                }else {
                    Toast.makeText(getContext(), R.string.editText_empty, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    };

    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }

    /**
     * The Handler that gets information back from the BluetoothChatService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            //Connected to a device.
                            onConversationStart();
                            newMessageText.setText("");
                            setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            setStatus(R.string.title_connecting);
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            setStatus(R.string.title_not_connected);
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    Log.d(TAG, "MESSAGE_WRITE RUNNING IN HANDLER");
                    Log.d(TAG, "I THINK IT IS SENDING A MESSAGE?");
                    String writeMessage = new String(writeBuf);
                    break;
                case Constants.MESSAGE_READ:
                    //When a message is received
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    onMessageReceived(readBuf);
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(getActivity(), R.string.bt_not_enabled_leaving,
                            Toast.LENGTH_SHORT).show();
                    getActivity().finish();
                }
        }
    }

    /**
     * Establish connection with other device
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device, secure);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.bluetooth_chat, menu);
        this.menu = menu;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.help_button: {
                //Show help screen!
                showHelp();
                return true;
            }
            case R.id.secure_connect_scan: {
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
                return true;
            }
            case R.id.insecure_connect_scan: {
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
                return true;
            }
            case R.id.discoverable: {
                // Ensure this device is discoverable by others
                ensureDiscoverable();
                return true;
            }

            case R.id.reset_userName: {
                setUserID();
                return true;
            }

            case R.id.panic_button: {
                if(!panicMode) {
                    //Turn on panic mode
                    panicMode = true;
                    populateListView();

                    //Change icon to on
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        //If running Android Lollipop or higher, use getDrawable(int, theme)
                        menu.getItem(1).setIcon(getResources().getDrawable(R.drawable.panic_icon_white, null));
                    }else{
                        //If running lower than Android Lollipop, use getDrawable(int)
                        menu.getItem(1).setIcon(getResources().getDrawable(R.drawable.panic_icon_white));
                    }
                    Log.d(TAG, "Panic mode on");

                }else{
                    //Turn off panic mode
                    panicMode = false;
                    populateListView();

                    //Change icon to off
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        //If running Android Lollipop or higher, use getDrawable(int, theme)
                        menu.getItem(1).setIcon(getResources().getDrawable(R.drawable.panic_icon_black, null));
                    }else{
                        //If running lower than Android Lollipop, use getDrawable(int)
                        menu.getItem(1).setIcon(getResources().getDrawable(R.drawable.panic_icon_black));
                    }
                    Log.d(TAG, "Panic mode off");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Sets up the database for a new conversation. It updates the currentTable variable
     * and loads any past messages if that table exists
     */
    protected void onConversationStart(){
        //Replace all removes all spaces from the string before setting the table name
        currentTable = (deviceID + mConnectedDeviceName).replaceAll("\\P{Alnum}", "");

        //This method checks if a table already exists, otherwise it creates one.
        dbHandler.createTable(currentTable);

        //Update myListView
        populateListView();
        Log.d(TAG, "New conversation initialized");
    }

    /**
     * Runs when a message is received through Bluetooth. Processes the received byte array
     * and tracks if messages are unread.
     *
     * @param bytes The serialized MessageData object containing the received message
     */
    protected void onMessageReceived(byte[] bytes){
        //This method is to be called when a bluetooth message is received

        //Convert byte[] to usable object
        MessageData md = convertFromBytes(bytes);

        //It adds the message to the database and refreshes the myListView
        dbHandler.addMessage(currentTable, md);

        if(inBack && unread.length() != 0){
            //If this is the first unread message, put the senderID at the top of the notification text
            unread.append(md.getSenderID());
            unread.append(": \n");
        }

        if(inBack){
            //The app is in the background, the message is unread
            unread.append(md.getMessage());
            unread.append("\n");
            //Create notification
            createNotification();
        }

        //Refresh the list view
        populateListView();
        Log.d(TAG, "Message received");
    }

    /**
     * Refreshes the messages shown in ListView, if panic mode is not active.
     */
    private void populateListView() {
        Cursor myCursor = dbHandler.getAllRows(currentTable);
        //What data you are going to populate the data with
        String[] fromFieldNames = new String[]{myDBHandler.COLUMN_MESSAGE_TEXT, myDBHandler.COLUMN_SENDER_ID, myDBHandler.COLUMN_TIME, myDBHandler.COLUMN_IMG_ID};

        //Where the data is going to go.
        int[] toViewIDs = new int[]{R.id.listRowMessage, R.id.listRowSender, R.id.listRowTime, R.id.listRowImage};

        //Define cursorAdapter, instantiated next line.
        SimpleCursorAdapter myCursorAdapter;
        //Get the context, the defined layout being used, the cursor, the columns being read, the location of info being stored, 0
        myCursorAdapter = new SimpleCursorAdapter(getContext(), R.layout.custom_row, myCursor, fromFieldNames, toViewIDs, 0);

        if(panicMode){
            //Do not show messages, set adapter to null
            myListView.setAdapter(null);
            Log.d(TAG, "Still in panic mode!");
        }else {
            //Messages can be shown; set myListView adapter to the cursorAdapter
            myListView.setAdapter(myCursorAdapter);
            Log.d(TAG, "ListView refreshed");
        }
    }

    /**
     * Builds and then creates a notification for unread messages. When clicked, the notification
     * returns you to the app.
     */
    private void createNotification(){
        //This will store and build the notification and its data
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getContext());
        //Set notification info
        mBuilder.setSmallIcon(R.drawable.logo_round);
        mBuilder.setContentTitle("SmackTalker: Unread Messages");
        mBuilder.setContentText(unread.toString());
        //Notification will disappear when clicked on.
        mBuilder.setAutoCancel(true);

        //Creates intent, with the context from MainActivity.
        Intent resultIntent = new Intent(getContext(), MainActivity.class);

        //This PendingIntent opens the MainActivity class (for when notification is clicked)
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        getContext(),
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        //Set the on notification click behaviour to PendingIntent
        mBuilder.setContentIntent(resultPendingIntent);

        NotificationManager mNotifyMgr = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);

        //notificationID allows you to update the notification later on.
        //mBuilder.build() returns a Notification containing above specifications.
        mNotifyMgr.notify(0, mBuilder.build());
    }

    /**
     * Deletes all notifications. This is called every time .onResume runs.
     */
    private void deleteNotifications(){
        NotificationManager mNotifyMgr = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.cancelAll();
    }

    /**
     * Creates a dialog box to set the userID for the phone.
     */
    private void setUserID(){
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getContext());

        //Create EditText to be used in dialog
        final EditText userIDText = new EditText(getContext());

        //Set dialog title
        //dialogBuilder.setTitle("Username");
        //Set dialog message
        dialogBuilder.setMessage(R.string.username_dialog_message);
        //Add edit text to dialog
        dialogBuilder.setView(userIDText);

        //This button will set userID
        dialogBuilder.setPositiveButton(R.string.username_set_button, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                userID = userIDText.getText().toString();
                Toast.makeText(getContext(), R.string.username_is_set, Toast.LENGTH_SHORT).show();

                //Stores the userID under the key specified in the final USERIDKEY
                editor.putString(USERIDKEY, userID);
                editor.apply();
            }
        });
        //This button will randomly generate a userID
        dialogBuilder.setNegativeButton(R.string.username_randomize_button, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                //Assign randomly generated userID

                int len = 8;
                StringBuilder sb = new StringBuilder( len );
                rnd = new SecureRandom();
                //Random # created which points to a string of all possible chars.
                //Appends that char onto the userID and repeats until desired length.
                for( int i = 0; i < len; i++ ) {
                    sb.append(AB.charAt(rnd.nextInt(AB.length())));
                }
                userID = sb.toString();
                Toast.makeText(getContext(), R.string.username_is_randomized, Toast.LENGTH_SHORT).show();

                //Stores the userID under the key specified in the final USERIDKEY
                editor.putString(USERIDKEY, userID);
                editor.apply();
            }
        });

        //Create the dialog
        AlertDialog aDialog = dialogBuilder.create();
        //Show the dialog
        aDialog.show();
    }

    /**
     * Converts a MessageData object to a byte array before it is sent through bluetooth
     * @param md The MessageData object to be serialized
     * @return The serialized MessageData object
     */
    private byte[] convertToBytes(MessageData md) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = null;
        byte[] mBytes = null;
        try {
            out = new ObjectOutputStream(bos);
            out.writeObject(md);
            mBytes = bos.toByteArray();
        }catch(Exception e){
            //Disregard this
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ex) {
                //Ignore close exception
            }
            try {
                bos.close();
            } catch (IOException ex) {
                //Ignore close exception
            }
        }
        return mBytes;
    }

    /**
     * Deserializes a MessageData object that was received from Bluetooth.
     * @param bytes The byte array to be converted back to a MessageData object
     * @return The deserialized MessageData object
     */
    private MessageData convertFromBytes(byte[] bytes) {
        MessageData md = null;

        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        ObjectInput in = null;
        try {
            in = new ObjectInputStream(bis);
            md = (MessageData) in.readObject();
        } catch (Exception e){
            //Disregard
        } finally {
            try {
                bis.close();
            } catch (IOException ex) {
                //Ignore close exception
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ex) {
                //Ignore close exception
            }
        }
        return md;
    }

    /**
     * Creates a dialog to display instructions for app use.
     */
    private void showHelp(){
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getContext());

        //Create TextView to be used in dialog
        final TextView textView = new TextView(getContext());
        textView.setText(R.string.instructions_content);
        textView.setPadding(25,25,25,25);

        //Set dialog title
        dialogBuilder.setTitle(R.string.instructions_title);
        //Set dialog message
        //dialogBuilder.setMessage(instructions);
        //Add edit text to dialog
        dialogBuilder.setView(textView);

        //Dismiss help
        dialogBuilder.setPositiveButton(R.string.okay, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        //Create the dialog
        AlertDialog aDialog = dialogBuilder.create();
        //Show the dialog
        aDialog.show();
    }

}
