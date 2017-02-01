package website.julianrosser.birthdays.activities;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.appindexing.Thing;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.squareup.picasso.Picasso;

import org.apache.commons.lang3.text.WordUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import website.julianrosser.birthdays.BirthdayReminder;
import website.julianrosser.birthdays.Constants;
import website.julianrosser.birthdays.R;
import website.julianrosser.birthdays.adapter.BirthdayViewAdapter;
import website.julianrosser.birthdays.fragments.DialogFragments.AddEditFragment;
import website.julianrosser.birthdays.fragments.DialogFragments.ItemOptionsFragment;
import website.julianrosser.birthdays.fragments.RecyclerListFragment;
import website.julianrosser.birthdays.model.Birthday;
import website.julianrosser.birthdays.model.events.BirthdayAlarmToggleEvent;
import website.julianrosser.birthdays.model.events.BirthdayItemClickEvent;
import website.julianrosser.birthdays.model.events.BirthdaysLoadedEvent;
import website.julianrosser.birthdays.model.tasks.LoadBirthdaysTask;
import website.julianrosser.birthdays.recievers.NotificationBuilderReceiver;
import website.julianrosser.birthdays.services.SetAlarmsService;
import website.julianrosser.birthdays.views.CircleTransform;

@SuppressWarnings("deprecation")
public class BirthdayListActivity extends BaseActivity implements AddEditFragment.NoticeDialogListener, ItemOptionsFragment.ItemOptionsListener, View.OnClickListener, GoogleApiClient.OnConnectionFailedListener {
    private static final int RC_SIGN_IN = 6006;
    public static ArrayList<Birthday> birthdaysList = new ArrayList<>();
    public Tracker mTracker;
    RecyclerListFragment recyclerListFragment;
    BirthdayListActivity mContext;
    Context mAppContext;
    private FloatingActionButton floatingActionButton;

    // Keys for orientation change reference
    final String ADD_EDIT_INSTANCE_KEY = "fragment_add_edit";
    final String ITEM_OPTIONS_INSTANCE_KEY = "fragment_item_options";
    final String RECYCLER_LIST_INSTANCE_KEY = "fragment_recycler_list";

    // Fragment references
    AddEditFragment addEditFragment;
    ItemOptionsFragment itemOptionsFragment;
    LoadBirthdaysTask loadBirthdaysTask;

    // App indexing
    private GoogleApiClient mClient;
    private String mUrl;
    private String mTitle;
    private String mDescription;

    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private NavigationView navigationView;

    // Sign In
    private GoogleApiClient mGoogleApiClient;
    private FirebaseAuth mAuth;
    private FirebaseAuth.AuthStateListener mAuthListener;

    private LinearLayout userDetailLayout;
    private SignInButton signInButton;
    private TextView textNavHeaderUserName;
    private TextView textNavHeaderEmail;
    private ImageView imageNavHeaderProfile;

    /**
     * For easy access to BirthdayListActivity context from multiple Classes
     */
//    public static BirthdayListActivity getContext() {
//        return mContext;
//    }
//
//    public static Context getAppContext() {
//        return mAppContext;
//    }

    // This builds an identical PendingIntent to the alarm and cancels when
    private void cancelAlarm(Birthday deletedBirthday) {

        // CreateIntent to start the AlarmNotificationReceiver
        Intent mNotificationReceiverIntent = new Intent(BirthdayListActivity.this,
                NotificationBuilderReceiver.class);

        // Create pending Intent using Intent we just built
        PendingIntent mNotificationReceiverPendingIntent = PendingIntent
                .getBroadcast(getApplicationContext(), deletedBirthday.getName().hashCode(),
                        mNotificationReceiverIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);

        // Finish by passing PendingIntent and delay time to AlarmManager
        AlarmManager mAlarmManager = (AlarmManager) getApplicationContext().getSystemService(ALARM_SERVICE);
        mAlarmManager.cancel(mNotificationReceiverPendingIntent);
    }

    /**
     * Save Birthdays to JSON file, then Update alarms by starting Service
     **/
    public void saveBirthdays()
            throws JSONException, IOException {

        if (birthdaysList != null) {

            try {
                // Build an array in JSON
                JSONArray array = new JSONArray();
                for (Birthday b : birthdaysList)
                    array.put(b.toJSON());

                // Write the file to disk
                Writer writer = null;
                try {
                    OutputStream out = mAppContext.openFileOutput(Constants.FILENAME,
                            Context.MODE_PRIVATE);
                    writer = new OutputStreamWriter(out);
                    writer.write(array.toString());

                } finally {
                    if (writer != null)
                        writer.close();
                }

            } catch (JSONException | IOException e) {
                e.printStackTrace();
            }

            // Launch service to update alarms when data changed
            Intent serviceIntent = new Intent(BirthdayListActivity.this, SetAlarmsService.class);
            startService(serviceIntent);
        }
    }

    // Force UI thread to ensure mAdapter updates RecyclerView list
    public void dataChangedUiThread() {
        // Reorder ArrayList to sort by desired method
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        // Get users sort preference
        if (Integer.valueOf(sharedPref.getString(getApplicationContext().getString(R.string.pref_sort_by_key), "0")) == 1) {
            BirthdayViewAdapter.sortBirthdaysByName();
        } else {
            BirthdayViewAdapter.sortBirthdaysByDate();
        }

        if (floatingActionButton != null && floatingActionButton.getVisibility() == View.INVISIBLE) {
            floatingActionButton.show();
        }

        RecyclerListFragment.mAdapter.notifyDataSetChanged();
        RecyclerListFragment.showEmptyMessageIfRequired();
    }

    public static boolean isContactAlreadyAdded(Birthday contact) {
        boolean onList = false;
        for (Birthday b : birthdaysList) {
            if (b.getName().equals(contact.getName())) {
                onList = true;
            }
        }
        return onList;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        setTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Pass toolbar as ActionBar for functionality
        Toolbar mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);

        navigationView = (NavigationView) findViewById(R.id.nav_view);
        //Setting Navigation View Item Selected FirebaseAuthListener to handle the item click of the navigation menu
        navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {

            // This method will trigger on item Click of navigation menu
            @Override
            public boolean onNavigationItemSelected(MenuItem menuItem) {

                //Checking if the item is in checked state or not, if not make it in checked state
                if (menuItem.isChecked()) menuItem.setChecked(false);
                else menuItem.setChecked(true);

                //Closing drawer on item click
                mDrawerLayout.closeDrawers();

                //Check to see which item was being clicked and perform appropriate action
                switch (menuItem.getItemId()) {

                    //Replacing the main content with ContentFragment Which is our Inbox View;
                    case R.id.menu_birthdays:
                        return true;
                    case R.id.menu_help:
                        startActivity(new Intent(getApplicationContext(), HelpActivity.class));
                        return true;
                    case R.id.menu_import_contacts:
                        checkContactPermissionAndLaunchImportActivity();
                        return true;
                    case R.id.menu_settings:
                        startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
                        return true;
                    default:
                        return true;
                }
            }
        });

        // Nav header info
        View headerView = navigationView.inflateHeaderView(R.layout.layout_nav_header);

        userDetailLayout = (LinearLayout) headerView.findViewById(R.id.layoutNavHeaderUserInfo);
        userDetailLayout.setOnClickListener(this);

        textNavHeaderUserName = (TextView) headerView.findViewById(R.id.navHeaderUserName);
        textNavHeaderEmail = (TextView) headerView.findViewById(R.id.navHeaderUserEmail);
        imageNavHeaderProfile = (ImageView) headerView.findViewById(R.id.profile_image);

        setUpGoogleSignInButton(headerView);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                mToolbar, R.string.birthday, R.string.button_negative) {

            /** Called when a drawer has settled in a completely closed state. */
            public void onDrawerClosed(View view) {
                super.onDrawerClosed(view);
                invalidateOptionsMenu(); // creates call to onPrepareOptionsMenu()
            }

            /** Called when a drawer has settled in a completely open state. */
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                invalidateOptionsMenu(); // creates call to onPrepareOptionsMenu()
            }
        };

        // Set the drawer toggle as the DrawerListener
        mDrawerLayout.setDrawerListener(mDrawerToggle);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_menu_white_24dp);
        getSupportActionBar().setHomeButtonEnabled(true);

        floatingActionButton = (FloatingActionButton) findViewById(R.id.floatingActionButton);
        floatingActionButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Open New Birthday Fragment
                showAddEditBirthdayFragment(AddEditFragment.MODE_ADD, 0);
            }
        });

        // Initialize context reference
        mContext = this;
        mAppContext = getApplicationContext();

        // Find RecyclerListFragment reference
        if (savedInstanceState != null) {
            //Restore the fragment's instance
            recyclerListFragment = (RecyclerListFragment) getSupportFragmentManager().getFragment(
                    savedInstanceState, RECYCLER_LIST_INSTANCE_KEY);

            itemOptionsFragment = (ItemOptionsFragment) getSupportFragmentManager().getFragment(
                    savedInstanceState, ITEM_OPTIONS_INSTANCE_KEY);

            addEditFragment = (AddEditFragment) getSupportFragmentManager().getFragment(
                    savedInstanceState, ADD_EDIT_INSTANCE_KEY
            );

        } else {
            // Create new RecyclerListFragment
            recyclerListFragment = RecyclerListFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, recyclerListFragment)
                    .commit();
        }

        // This is to help the fragment keep its state on rotation
        recyclerListFragment.setRetainInstance(true);

        // Obtain the shared Tracker instance.
        mTracker = getDefaultTracker();

        mClient = new GoogleApiClient.Builder(this).addApi(AppIndex.API).build();
        mUrl = "http://julianrosser.website";
        mTitle = "Birthday Reminders";
        mDescription = "Simple birthday reminders for loved-ones";

        if (getIntent().getExtras() != null && getIntent().getExtras().getInt(Constants.INTENT_FROM_KEY, 10) == Constants.INTENT_FROM_NOTIFICATION) {
            mTracker.send(new HitBuilders.EventBuilder()
                    .setCategory("Action")
                    .setAction("Notification Touch")
                    .build());
        }

        // Get sample of theme choice
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Random r = new Random();
        if (r.nextInt(10) == 5) {
            mTracker.send(new HitBuilders.EventBuilder()
                    .setCategory("Samples")
                    .setAction("Theme")
                    .setValue(Long.valueOf(prefs.getString(getResources().getString(R.string.pref_theme_key), "0")))
                    .build());
        }
    }

    /**
     * Google Authentication methods
     */

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
            handleSignInResult(result);
        }
    }

    private void handleSignInResult(GoogleSignInResult result) {
        Log.d("SIGN IN", "handleSignInResult:" + result.isSuccess());
        if (result.isSuccess()) {
            // Signed in successfully, show authenticated UI.
            GoogleSignInAccount account = result.getSignInAccount();
            firebaseAuthWithGoogle(account);
        } else {
            setNavHeaderUserState(NavHeaderState.LOGGED_OUT);
            Snackbar.make(floatingActionButton, "Error while logging in", Snackbar.LENGTH_SHORT).show(); // todo - translate
        }
    }

    public void firebaseAuthWithGoogle(GoogleSignInAccount account) {
        Log.d("Auth", "firebaseAuthWithGoogle: " + account.getId());

        AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        Log.d("Auth", "signInWithCredential:onComplete:" + task.isSuccessful());

                        // If sign in fails, display a message to the user. If sign in succeeds
                        // the auth state listener will be notified and logic to handle the
                        // signed in user can be handled in the listener.
                        if (!task.isSuccessful()) {
                            Log.w("Auth", "signInWithCredential", task.getException());
                            Toast.makeText(BirthdayListActivity.this, "Authentication failed.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void setUpGoogleSignInButton(View headerView) {
        GoogleSignInOptions gso = setUpGoogleSignInOptions();
        signInButton = (SignInButton) headerView.findViewById(R.id.sign_in_button);
        signInButton.setSize(SignInButton.SIZE_WIDE);
        signInButton.setScopes(gso.getScopeArray());
        signInButton.setOnClickListener(this);

        mAuth = FirebaseAuth.getInstance();
        mAuthListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    // User is signed in
                    handleUserAuthenticated(user);
                    Log.d("Auth", "onAuthStateChanged:signed_in:" + user.getUid());
                } else {
                    // User is signed out
                    setNavHeaderUserState(NavHeaderState.LOGGED_OUT);
                    Snackbar.make(floatingActionButton, "Signed OUT", Snackbar.LENGTH_SHORT).show();
                    Log.d("Auth", "onAuthStateChanged:signed_out");
                }
            }
        };
    }

    private void handleUserAuthenticated(FirebaseUser user) {
        textNavHeaderUserName.setText(user.getDisplayName());
        textNavHeaderEmail.setText(user.getEmail());
        Picasso.with(getApplicationContext()).load(user.getPhotoUrl()).transform(new CircleTransform()).into(imageNavHeaderProfile);
        setNavHeaderUserState(NavHeaderState.SIGNED_IN);
        Snackbar.make(floatingActionButton, user.getDisplayName() + "signed IN | " + user.getEmail(), Snackbar.LENGTH_SHORT).show();
    }

    private GoogleSignInOptions setUpGoogleSignInOptions() {
        // Configure sign-in to request the user's ID, email address, and basic
        // profile. ID and basic profile are included in DEFAULT_SIGN_IN.
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(Constants.GOOGLE_SIGN_IN_KEY)
                .requestEmail()
                .build();

        // Build a GoogleApiClient with access to the Google Sign-In API and the
        // options specified by gso.
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .enableAutoManage(this /* FragmentActivity */, this /* OnConnectionFailedListener */)
                .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
                .build();
        return gso;
    }

    enum NavHeaderState {
        LOGGED_OUT,
        SIGNED_IN
    }

    public void setNavHeaderUserState(NavHeaderState state) {
        switch (state) {
            case LOGGED_OUT:
                userDetailLayout.setVisibility(View.GONE);
                signInButton.setVisibility(View.VISIBLE);
                imageNavHeaderProfile.setVisibility(View.GONE);
                break;
            case SIGNED_IN:
                userDetailLayout.setVisibility(View.VISIBLE);
                signInButton.setVisibility(View.GONE);
                imageNavHeaderProfile.setVisibility(View.VISIBLE);
                break;
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Snackbar.make(floatingActionButton, "Sign in failed", Snackbar.LENGTH_SHORT).show();
    }

    public Action getAction() {
        Thing object = new Thing.Builder()
                .setName(mTitle)
                .setDescription(mDescription)
                .setUrl(Uri.parse(mUrl))
                .build();

        return new Action.Builder(Action.TYPE_VIEW)
                .setObject(object)
                .setActionStatus(Action.STATUS_TYPE_COMPLETED)
                .build();
    }

    @Override
    public void onStart() {
        super.onStart();
        mClient.connect();
        mAuth.addAuthStateListener(mAuthListener);
        AppIndex.AppIndexApi.start(mClient, getAction());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Remove context to prevent memory leaks
        mContext = null;

        // Cancel the task if it's running
        if (isTaskRunning()) {
            loadBirthdaysTask.cancel(true);
        }

        loadBirthdaysTask = null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mContext == null) {
            mContext = this;
        }
        if (mAppContext == null) {
            mAppContext = getApplicationContext();
        }

        dataChangedUiThread();

        // Tracker
        mTracker.setScreenName("BirthdayListActivity");
        mTracker.send(new HitBuilders.ScreenViewBuilder().build());

        EventBus.getDefault().register(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onStop() {
        try {
            saveBirthdays();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Random r = new Random();
        if (r.nextInt(10) == 5) {
            mTracker.send(new HitBuilders.EventBuilder()
                    .setCategory("Data")
                    .setAction("Birthdays count")
                    .setValue(birthdaysList.size())
                    .build());
        }
        if (mAuthListener != null) {
            mAuth.removeAuthStateListener(mAuthListener);
        }
        AppIndex.AppIndexApi.end(mClient, getAction());
        mClient.disconnect();
        super.onStop();
    }

    synchronized public Tracker getDefaultTracker() {
        if (mTracker == null) {
            GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
            // To enable debug logging use: adb shell setprop log.tag.GAv4 DEBUG
            mTracker = analytics.newTracker(R.xml.global_tracker);
        }
        return mTracker;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (recyclerListFragment != null && recyclerListFragment.isAdded()) {
            //Save the fragment's instance (IF THEY EXIST!)
            getSupportFragmentManager().putFragment(outState, RECYCLER_LIST_INSTANCE_KEY, recyclerListFragment);
        }

        if (itemOptionsFragment != null && itemOptionsFragment.isAdded()) {
            getSupportFragmentManager().putFragment(outState, ITEM_OPTIONS_INSTANCE_KEY, itemOptionsFragment);
        }
        if (addEditFragment != null && addEditFragment.isAdded()) {
            getSupportFragmentManager().putFragment(outState, ADD_EDIT_INSTANCE_KEY, addEditFragment);
        }
    }

    public void showAddEditBirthdayFragment(int mode, int birthdayListPosition) {
        // Create an instance of the dialog fragment and show it
        addEditFragment = AddEditFragment.newInstance();

        // Create bundle for storing mode information
        Bundle bundle = new Bundle();
        // Pass mode parameter onto Fragment
        bundle.putInt(AddEditFragment.MODE_KEY, mode);

        // If we are editing an old birthday, pass its information to fragment
        if (mode == AddEditFragment.MODE_EDIT) {
            // Reference to birthday we're editing
            Birthday editBirthday = birthdaysList.get(birthdayListPosition);
            // Pass birthday's data to Fragment
            bundle.putInt(AddEditFragment.DATE_KEY, editBirthday.getDate().getDate());
            bundle.putInt(AddEditFragment.MONTH_KEY, editBirthday.getDate().getMonth());
            bundle.putInt(AddEditFragment.YEAR_KEY, editBirthday.getYear());
            bundle.putInt(AddEditFragment.POS_KEY, birthdayListPosition);
            bundle.putString(AddEditFragment.NAME_KEY, editBirthday.getName());
            bundle.putBoolean(AddEditFragment.SHOW_YEAR_KEY, editBirthday.shouldIncludeYear());
        }

        // Pass bundle to Dialog, get FragmentManager and show
        addEditFragment.setArguments(bundle);
        addEditFragment.show(getSupportFragmentManager(), "AddEditBirthdayFragment");
    }

    // This method creates and shows a new ItemOptionsFragment, this replaces ContextMenu
    public void showItemOptionsFragment(int position) {
        // Create an instance of the dialog fragment and show it
        itemOptionsFragment = ItemOptionsFragment.newInstance(position);
        itemOptionsFragment.setRetainInstance(true);
        itemOptionsFragment.show(getSupportFragmentManager(), "AddEditBirthdayFragment");
    }

    // Callback from AddEditFragment, create new Birthday object and add to array
    @Override
    public void onDialogPositiveClick(AddEditFragment dialog, String name, int day, int month, int year, boolean includeYear, int addEditMode, final int position) {

        // Build date object which will be used by new Birthday
        Date dateOfBirth = new Date();
        dateOfBirth.setYear(year);
        dateOfBirth.setMonth(month);
        dateOfBirth.setDate(day);


        // Format name by capitalizing name
        name = WordUtils.capitalize(name);

        final Birthday birthday;

        // Decide whether to create new or edit old birthday
        if (addEditMode == AddEditFragment.MODE_EDIT) {
            // Edit text
            birthday = birthdaysList.get(position);
            birthday.edit(name, dateOfBirth, true, includeYear);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {

                    /** Logic for edit animation. Depending first on sorting preference, check whether the sorting will change.
                     * if so, used adapter moved animation, else just refresh information */
                    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

                    // Get users sorting preference
                    if (Integer.valueOf(sharedPref.getString(getApplicationContext().getString(R.string.pref_sort_by_key), "0")) != 1) {

                        // PREF SORT: DATE
                        if (BirthdayViewAdapter.willChangeDateOrder(birthday)) {

                            // Order will change, sort, then notify adapter of move
                            BirthdayViewAdapter.sortBirthdaysByDate();
                            RecyclerListFragment.mAdapter.notifyItemMoved(position, birthdaysList.indexOf(birthday));
                        } else {
                            // No order change, so just notify item changed
                            RecyclerListFragment.mAdapter.notifyItemChanged(birthdaysList.indexOf(birthday));
                        }
                    } else {

                        // PREF SORT: NAME. If order changes, sort the notify adapter
                        if (BirthdayViewAdapter.willChangeNameOrder(birthday)) {

                            // Order will change, sort, then notify adapter of move
                            BirthdayViewAdapter.sortBirthdaysByName();
                            RecyclerListFragment.mAdapter.notifyItemMoved(position, birthdaysList.indexOf(birthday));

                        } else {
                            // order not changes, so forget sot, and just notify item changed
                            RecyclerListFragment.mAdapter.notifyItemChanged(birthdaysList.indexOf(birthday));
                        }
                    }
                    // Delay update until after animation has finished
                    Runnable r = new Runnable() {
                        public void run() {
                            RecyclerListFragment.mAdapter.notifyDataSetChanged();
                        }
                    };
                    new Handler().postDelayed(r, 500);
                }
            });

        } else {
            // Create birthday, add to array and notify adapter
            birthday = new Birthday(name, dateOfBirth, true, includeYear);
            birthdaysList.add(birthday);

            // Notify adapter
            runOnUiThread(new Runnable() {
                public void run() {

                    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

                    // Get users sort preference
                    if (Integer.valueOf(sharedPref.getString(getApplicationContext().getString(R.string.pref_sort_by_key), "0")) == 1) {
                        BirthdayViewAdapter.sortBirthdaysByName();
                    } else {
                        BirthdayViewAdapter.sortBirthdaysByDate();
                    }
                    RecyclerListFragment.mAdapter.notifyItemInserted(birthdaysList.indexOf(birthday));
                    RecyclerListFragment.showEmptyMessageIfRequired();
                }
            });

            mTracker.send(new HitBuilders.EventBuilder()
                    .setCategory("Action")
                    .setAction("New Birthday")
                    .build());
        }

        try {
            saveBirthdays();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // We only use this method to delete data from Birthday array and pass a reference to the cancel alarm method.
    public void deleteFromArray(final int position) {

        // Cancel the notification PendingIntent
        cancelAlarm(birthdaysList.get(position));

        // Notify adapter
        mContext.runOnUiThread(new Runnable() {
            public void run() {

                final Birthday birthdayToDelete = birthdaysList.get(position);

                birthdaysList.remove(position);

                RecyclerListFragment.mAdapter.notifyItemRemoved(position);
                RecyclerListFragment.showEmptyMessageIfRequired();

                if (floatingActionButton != null && floatingActionButton.getVisibility() == View.INVISIBLE) {
                    floatingActionButton.show();
                }
                try {
                    saveBirthdays();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Snackbar.make(floatingActionButton, getApplicationContext().getString(R.string.deleted) + " "
                        + birthdayToDelete.getName(), Snackbar.LENGTH_LONG).setAction(R.string.undo,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                birthdaysList.add(birthdayToDelete);
                                // Notify adapter
                                mContext.runOnUiThread(new Runnable() {
                                    public void run() {

                                        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

                                        // Get users sort preference
                                        if (Integer.valueOf(sharedPref.getString(getApplicationContext().getString(R.string.pref_sort_by_key), "0")) == 1) {
                                            BirthdayViewAdapter.sortBirthdaysByName();
                                        } else {
                                            BirthdayViewAdapter.sortBirthdaysByDate();
                                        }

                                        RecyclerListFragment.mAdapter.notifyItemInserted(birthdaysList.indexOf(birthdayToDelete));
                                        RecyclerListFragment.showEmptyMessageIfRequired();
                                    }
                                });
                                try {
                                    saveBirthdays();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }).show();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_sign_out) {
            signOutGoogle();
            return true;
        } else if (id == R.id.action_firebase) {
            performFirebaseAction();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void performFirebaseAction() {
        DatabaseReference dbr = BirthdayReminder.getInstance().getDatabaseReference();
        for (int i = 0; i < birthdaysList.size(); i++) {
            dbr.child("" + i).setValue(birthdaysList.get(i).getName());
        }
    }

    private void signOutGoogle() {
        Auth.GoogleSignInApi.signOut(mGoogleApiClient).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        setNavHeaderUserState(NavHeaderState.LOGGED_OUT);
                        Snackbar.make(floatingActionButton, "signOutGoogle: " + status.getStatusMessage(), Snackbar.LENGTH_SHORT).show();
                    }
                });
    }

    private void revokeAccessGoogle() {
        Auth.GoogleSignInApi.revokeAccess(mGoogleApiClient).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        Snackbar.make(floatingActionButton, "revokeAccessGoogle: " + status.getStatusMessage(), Snackbar.LENGTH_SHORT).show();
                    }
                });
    }

    // Call this method from Adapter so reference can be kept here in BirthdayListActivity
    public void launchLoadBirthdaysTask() {
        loadBirthdaysTask = new LoadBirthdaysTask();
        loadBirthdaysTask.execute();
    }

    // Check if Async task is currently running, to prevent errors when exiting
    private boolean isTaskRunning() {
        return (loadBirthdaysTask != null) && (loadBirthdaysTask.getStatus() == AsyncTask.Status.RUNNING);
    }

    // Set theme based on users preference
    // todo - refactor
    public void setTheme() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        if (prefs.getString(getResources().getString(R.string.pref_theme_key), "0").equals("0")) {
            setTheme(R.style.BlueTheme);
        } else if (prefs.getString(getResources().getString(R.string.pref_theme_key), "0").equals("1")) {
            setTheme(R.style.PinkTheme);
        } else if (prefs.getString(getResources().getString(R.string.pref_theme_key), "0").equals("2")) {
            setTheme(R.style.GreenTheme);
        } else {
            setTheme(R.style.PinkTheme);
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();

        switch (id) {
            case R.id.layoutNavHeaderUserInfo:
                Snackbar.make(v, "Change user display", Snackbar.LENGTH_SHORT).show();
                break;
            case R.id.sign_in_button:
                signIn();
                break;
        }
    }

    @Subscribe
    public void onMessageEvent(BirthdaysLoadedEvent event) {
        birthdaysList = event.getBirthdays();
        RecyclerListFragment.showEmptyMessageIfRequired();
    }

    @Subscribe
    public void onAlarmToggle(BirthdayAlarmToggleEvent event) {
        alarmToggled(event.getCurrentPosition());
    }

    @Subscribe
    public void onBirthdayClicked(BirthdayItemClickEvent event) {
        showItemOptionsFragment(event.getCurrentPosition());
    }

    private void signIn() {
        Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    public void setTitle(CharSequence title) {
        mTitle = (String) title;
        getActionBar().setTitle(mTitle);
    }

    /**
     * Interface Methods
     * - onItemEdit: Launch AddEditFragment to edit current birthday
     * - onItemDelete: Delete selected birthday from array
     */
    @Override
    public void onItemEdit(ItemOptionsFragment dialog, int position) {
        itemOptionsFragment.dismiss();
        showAddEditBirthdayFragment(AddEditFragment.MODE_EDIT, position);

        mTracker.send(new HitBuilders.EventBuilder()
                .setCategory("Action")
                .setAction("Edit")
                .build());
    }

    @Override
    public void onItemDelete(ItemOptionsFragment dialog, int position) {
        itemOptionsFragment.dismiss();
        deleteFromArray(position);

        mTracker.send(new HitBuilders.EventBuilder()
                .setCategory("Action")
                .setAction("Delete")
                .build());
    }

    @Override
    public void onItemToggleAlarm(ItemOptionsFragment dialog, int position) {
        itemOptionsFragment.dismiss();
        // Change birthdays remind bool
        birthdaysList.get(position).toggleReminder();
        alarmToggled(position);

        mTracker.send(new HitBuilders.EventBuilder()
                .setCategory("Action")
                .setAction("Toggle Alarm OPTION")
                .build());
    }

    // This is in a separate method so it can be called from different classes
    public void alarmToggled(int position) {

        // Use position parameter to get Birthday reference
        Birthday birthday = birthdaysList.get(position);

        // Cancel the previously set alarm, without re-calling service
        cancelAlarm(birthday);

        // Notify adapter of change, so that UI is updated
        dataChangedUiThread();

        // Notify user of change. If birthday is today, let user know alarm is set for next year
        if (birthday.getDaysBetween() == 0 && birthday.getRemind()) {
            Snackbar.make(floatingActionButton, getApplicationContext().getString(R.string.reminder_for) + birthday.getName() + " " +
                    birthday.getReminderString() + getApplicationContext().getString(R.string.for_next_year), Snackbar.LENGTH_LONG).show();
        } else {
            Snackbar.make(floatingActionButton, getApplicationContext().getString(R.string.reminder_for) + birthday.getName() + " " +
                    birthday.getReminderString(), Snackbar.LENGTH_LONG).show();
        }

        // Attempt to save updated Birthday data
        try {
            saveBirthdays();
        } catch (Exception e) {
            e.printStackTrace();
        }

        mTracker.send(new HitBuilders.EventBuilder()
                .setCategory("Action")
                .setAction("Toggle Alarm")
                .build());
    }

    public void checkContactPermissionAndLaunchImportActivity() {
        // Here, thisActivity is the current activity
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {

            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    Constants.CONTACT_PERMISSION_CODE);

            // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
            // app-defined int constant. The callback method gets the
            // result of the request.
        } else {
            launchImportContactActivity();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (requestCode == Constants.CONTACT_PERMISSION_CODE) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchImportContactActivity();

            } else {
                // permission denied, boo! Disable the
                // functionality that depends on this permission.
                Snackbar.make(floatingActionButton, R.string.contact_permission_denied_message, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    public void launchImportContactActivity() {
        Intent intent = new Intent(this, ImportContactsActivity.class);
        startActivity(intent);
    }
}