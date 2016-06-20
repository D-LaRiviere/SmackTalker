package com.ded.smacktalker;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Handles interactions between the app and the database
 */
public class myDBHandler extends SQLiteOpenHelper{

    //If you update the structure of the database, change this constant for compatibility
    private static final int DATABASE_VERSION = 6;
    //Name of database, must end in .db
    private static final String DATABASE_NAME = "SmackTalker.db";
    //Name of the table within the database
    public static final String TABLE_MESSAGES = "messagehistory";

    //Every column in the table should have its own constant here.
    protected static final String COLUMN_ID = "_id";
    protected static final String COLUMN_MESSAGETEXT = "message";
    protected static final String COLUMN_SENDERID = "senderid";
    protected static final String COLUMN_TIME = "time";
    protected static final String COLUMN_IMGID = "imgid";

    protected static final String[] allColumns = {COLUMN_ID, COLUMN_MESSAGETEXT, COLUMN_SENDERID, COLUMN_TIME, COLUMN_IMGID};

    private static final String TAG = "myDBHandler";

    /**
     * Constructor that creates the database
     * @param context Context of the activity
     */
    public myDBHandler(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        //For housekeeping, passes information to the super class which does background stuff.
    }

    /**
     * Creates first table when Database is created
     * @param db The database to create a table in
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        //What happens when the database is created for the first time.

        //This string is an SQL command which will create the table.
        //For the columns in the table, you must add them so the string
        //Looks like tableName(column1 <>, column2 <>, column3 <>);
        //Where the <> is the details about that column
        String query = "CREATE TABLE " + TABLE_MESSAGES + "(" +
                COLUMN_ID + " INTERGER PRIMARY KEY, " + //Unique int identifier, automatically incrementing
                COLUMN_MESSAGETEXT + " TEXT, " +
                COLUMN_SENDERID + " TEXT, " +
                COLUMN_TIME + " INTEGER, " +
                COLUMN_IMGID + " INTEGER " +
                ");";

        //Passes the above command to SQL to execute
        db.execSQL(query);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        //What happens when you upgrade the version of the database.
        //Delete old table
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        //Call onCreate to make a new table.
        onCreate(db);
        Log.d(TAG, "Table Dropped");
    }

    /**
     * Creates a table to store a conversation if one does not already exist
     *
     * @param table The table to be created
     */
    public void createTable(String table){
        //Creates a database we can write to!
        SQLiteDatabase db = getWritableDatabase();

        //Create a table to store a specific conversation
        //This string is an SQL command which will create the table.
        //For the columns in the table, you must add them so the string
        //Looks like tableName(column1 <>, column2 <>, column3 <>);
        //Where the <> is the details about that column

        String query = "CREATE TABLE IF NOT EXISTS " + table + "(" +
                COLUMN_ID + " INTERGER PRIMARY KEY, " + //Unique int identifier, automatically incrementing
                COLUMN_MESSAGETEXT + " TEXT, " +
                COLUMN_SENDERID + " TEXT, " +
                COLUMN_TIME + " INTEGER, " +
                COLUMN_IMGID + " INTEGER " +
                ");";

        //Passes the above command to SQL to execute
        db.execSQL(query);
    }

    /**
     * Adds a new row of information to a specified table
     *
     * @param table The table to access
     * @param md The MessageData object containing the message and info
     */
    public void addMessage(String table, MessageData md){
        //Allows you to set values for several columns for one row, in one go.
        ContentValues values = new ContentValues();

        //Adds the data to its respective columns
        values.put(COLUMN_MESSAGETEXT, md.getMessage());
        values.put(COLUMN_SENDERID, md.getSenderID());
        values.put(COLUMN_TIME, md.getTime());
        values.put(COLUMN_IMGID, md.getImgID());

        //Creates a database we can write to!
        SQLiteDatabase db = getWritableDatabase();

        //Inserts a new row in
        db.insert(table, null, values);
        Log.d(TAG, "Data passed into DB");

        //Closes database, saves android some memory.
        db.close();
    }

    /**
     * Deletes a row from a specified table
     *
     * @param table The table to access data from
     * @param messageID The message to be deleted
     */
    public void deleteMessage(String table, String messageID){
        //Creates a database we can delete from!
        SQLiteDatabase db = getWritableDatabase();

        //Deletes from the table, where the MESSAGETEXT column matches the parameter passed in.
        db.execSQL("DELETE FROM " + table + " WHERE " + COLUMN_MESSAGETEXT +
                "=\"" + messageID + "\";");

        //Close database
        db.close();
    }

    /**
     * Returns all the messages in a specified table
     *
     * @param table The table to access data from
     * @return String of all the messages in the table
     */
    public String databaseToString(String table){
        String dbString = "";

        //Creates a database we can write to!
        SQLiteDatabase db = getWritableDatabase();

        //"Select all from the Table where all conditions are met (1 means this is always true)
        String query = "SELECT * FROM " + table + " WHERE 1";

        //Cursor will point to a location in your results.
        Cursor c = db.rawQuery(query, null);
        //Move to the first row in your results
        c.moveToFirst();

        while(!c.isAfterLast()){
            //While there is still results to go
            if (c.getString(c.getColumnIndex(COLUMN_MESSAGETEXT)) != null){
                dbString += c.getString(c.getColumnIndex(COLUMN_MESSAGETEXT));

                //Adds a new line
                dbString += "\n";
            }
        }
        db.close();
        c.close();
        return dbString;
    }

    /**
     * Returns all the data in a specified table
     *
     * @param table The table to access data from
     * @return Contains all data from the table in a cursor
     */
    public Cursor getAllRows(String table){
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.query(true, table, allColumns , null, null, null, null, null, null );
        if(c != null){
            c.moveToFirst();
        }
        return c;
    }

    /**
     * Determines the number of rows in a table
     *
     * @param table The table to access data from
     * @return Number of rows in table
     */
    //Get Row Count
    public int getCount(String table) {
        String countQuery = "SELECT  * FROM " + table;
        int count = 0;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(countQuery, null);
        if(cursor != null && !cursor.isClosed()){
            count = cursor.getCount();
            cursor.close();
        }
        return count;
    }

}
