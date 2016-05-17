package data.hci.gdatawatch.Activity;


import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;

import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import data.hci.gdatawatch.Adapter.EventAdapter;
import data.hci.gdatawatch.Global.StaticVariable;
import data.hci.gdatawatch.Oauth.AuthPreferences;
import data.hci.gdatawatch.R;


public class CalendarActivity extends AppCompatActivity {
    AccountManager accountManager;
    AuthPreferences authPreferences;

    GoogleAccountCredential credential;

    Button addEvent;
    private final String SCOPE="https://www.googleapis.com/auth/calendar";

    RecyclerView recyclerView;
    LinearLayoutManager layoutManager;
    ProgressBar progressBar;
    Handler handler;
    EventAdapter adapter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar);

        checkAccountPermission();
        init();


    }

    protected void init(){
        handler = new Handler();//recyclerview ui 업데이트 핸들러

        recyclerView = (RecyclerView)findViewById(R.id.calendar_event_list); //이벤트 아이템 뷰
        layoutManager = new LinearLayoutManager(getApplicationContext());//아이템의 항목을 배치
        recyclerView.setLayoutManager(layoutManager);

        progressBar = (ProgressBar)findViewById(R.id.progressBar_calendar); //이벤트 아이템 뷰가 나타나기 전까지의 프로그래스 바

        addEvent = (Button)findViewById(R.id.add_event_button);
        addEvent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(getApplicationContext(), AddEventActivity.class), StaticVariable.CALENDAR_ACCEPT);
            }
        });

        accountManager = AccountManager.get(this);
        authPreferences = new AuthPreferences(this);
        credential = GoogleAccountCredential.usingOAuth2(getApplicationContext(), Arrays.asList(StaticVariable.CALENDAR_SCOPES)) //credential 초기화!
                .setBackOff(new ExponentialBackOff());
        if(authPreferences.getUser() != null && authPreferences.getToken() != null)
            doAuthenticatedStuff();
        else
            chooseAccount();
    }

    /**
     * 권한 설정
     * */
    private void checkAccountPermission(){
        //마시멜로우 버전 이상일 때만 권한 설정이 적용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            //권한이 없을 때
            if(checkSelfPermission(Manifest.permission.ACCOUNT_MANAGER) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.ACCOUNT_MANAGER, Manifest.permission.GET_ACCOUNTS,
                        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, StaticVariable.ACCOUNT_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case StaticVariable.ACCOUNT_PERMISSION:
                if(grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[2] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[3] == PackageManager.PERMISSION_GRANTED){   Log.d("Calendar Activity","Account access");}
                else {
                    Log.d("Calendar Activity", "Account deny");
                    finish();
                }
                Log.d("Calendar Activity", grantResults.toString());
            break;
        }
    }

    private void doAuthenticatedStuff(){
        Log.d("Token", authPreferences.getToken());
        //이미 기존의 값이 있다면 기존 값으로 credentail 초기화
        credential.setSelectedAccountName(authPreferences.getUser());

        new MakeRequestTask(credential).execute();
    }

    /**
     * 어플리케이션과 연동할 Account 선택
     * */
    private void chooseAccount(){
        Intent intent = AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"}, false, null, null, null, null);
        startActivityForResult(intent, StaticVariable.REQUEST_PICK_ACCOUNT);
    }

    /**
     * 구글에 토큰을 요청
     * */
    private void requestToken(){
        Account userAccount = null;
        String user = authPreferences.getUser();
        Log.d("Account user", user);

        Account []account = accountManager.getAccountsByType("com.google");

        for(int i = 0 ; i < accountManager.getAccountsByType("com.google").length; i++){
            Log.d("Account name", account[i].name);
            if(account[i].name.equals(user)) {
                userAccount = account[i];
                break;
            }
        }

        accountManager.getAuthToken(userAccount, "oauth2:" + SCOPE, null, this, new OnTokenAcquired(), null);
    }

    /**
     * 기존의 토큰을 없애버리는 함수
     * */
    private void invalidateToken(){
        AccountManager accountManager = AccountManager.get(this);
        accountManager.invalidateAuthToken("com.google", authPreferences.getToken());
        authPreferences.setToken(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d("request code", requestCode + "");
        switch (requestCode){
            case StaticVariable.REQUEST_AUTHORIZATION://승인이 된 account의 토큰 요청
                if(resultCode == RESULT_OK){
                    requestToken();
                }
                break;

            case StaticVariable.REQUEST_PICK_ACCOUNT://Account를 고르는 요청
                if(resultCode == RESULT_OK && data!=null) {
                    String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    credential.setSelectedAccountName(accountName);//선택된 account를 credential에 설정
                    authPreferences.setUser(accountName);
                    invalidateToken();

                    requestToken();
                }
                break;

            case StaticVariable.CALENDAR_ACCEPT:
                if(resultCode == RESULT_OK)
                    new MakeRequestTask(credential).execute();
                break;
        }
    }

    public void addData(final ArrayList<String>eventName, final ArrayList<String>eventPlace, final ArrayList<String>eventStart, final ArrayList<String>eventEnd, final ArrayList<String>eventPerson){
        handler.post(new Runnable() {
            @Override
            public void run() {
                adapter = new EventAdapter();
                for(int i = 0; i<eventStart.size();i++){
                    adapter.addItems(eventName.get(i), eventPlace.get(i), eventStart.get(i), eventEnd.get(i), eventPerson.get(i));
                }
                recyclerView.setAdapter(adapter);
                progressBar.setVisibility(View.INVISIBLE);
            }
        });
    }

    private class OnTokenAcquired implements AccountManagerCallback<Bundle> {

        @Override
        public void run(AccountManagerFuture<Bundle> result) {
            try {
                Bundle bundle = result.getResult();

                Intent launch = (Intent) bundle.get(AccountManager.KEY_INTENT);
                if (launch != null) {
                    startActivityForResult(launch, StaticVariable.REQUEST_AUTHORIZATION);
                } else {
                    String token = bundle
                            .getString(AccountManager.KEY_AUTHTOKEN);

                    authPreferences.setToken(token);

                    doAuthenticatedStuff();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class MakeRequestTask extends AsyncTask<Void, Void, List<String>>{
        Calendar service = null;
        Exception error = null;

        public MakeRequestTask(GoogleAccountCredential credential){
            progressBar.setVisibility(View.VISIBLE);
            HttpTransport transport = AndroidHttp.newCompatibleTransport();
            com.google.api.client.json.JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
            service = new Calendar.Builder(transport, jsonFactory, credential)
                    .setApplicationName(String.valueOf(R.string.app_name))
                    .build();
        }

        @Override
        protected List<String> doInBackground(Void... params) {
            try{
                Log.d("make request task", "execute");
                return getDataFromApi();
            }catch (UserRecoverableAuthIOException e){//다시 계정을 선택한다.
                startActivityForResult(e.getIntent(), StaticVariable.REQUEST_PICK_ACCOUNT);
                return null;
            }catch (Exception e){
                e.printStackTrace();
                error = e;
                e.printStackTrace();
                cancel(true);
                return null;
            }
        }

        /**
         * Google Calender로부터 이벤트를 받아오는 함수
         * */
        private List<String> getDataFromApi() throws IOException{
            List<String> eventStrings = new ArrayList<String>();

            Events events = service.events().list("primary")
                    .setOrderBy("startTime")//이벤트 시작 순서
                    .setSingleEvents(true)
                    .execute();// Google로부터 내 캘린더 이벤트를 받아온다.

            List<Event> items = events.getItems();//캘린더 이벤트 집합

            ArrayList<String> name = new ArrayList<String>();
            ArrayList<String> place = new ArrayList<String>();
            ArrayList<String> startDate = new ArrayList<String>();
            ArrayList<String> endDate = new ArrayList<String>();
            ArrayList<String> person = new ArrayList<String>();

            for(Event event : items){
                DateTime start = event.getStart().getDateTime();
                DateTime end = event.getEnd().getDateTime();
                String eventName = event.getSummary();
                String eventPlace = event.getLocation();
                String eventPerson = event.getDescription();

                if(eventName == null) { eventName = "no event name"; }
                if(eventPlace == null ){ eventPlace = "no place"; }
                if(eventPerson == null || eventPerson.equals("person=")) { eventPerson = "no person"; }
                if(start == null){   start = event.getStart().getDate();  }
                if(end == null){    end = event.getEnd().getDate(); }

                Log.d("event String", eventName+" "+eventPlace+" "+start.toString()+" "+end.toString()+" "+eventPerson);

                name.add(eventName); place.add(eventPlace); startDate.add(start.toString()); endDate.add(end.toString()); person.add(eventPerson);
                eventStrings.add(String.format("%s (%s) ~ (%s)", event.getSummary(), start, end));
            }
            addData(name, place, startDate, endDate, person);
            return eventStrings;//모든 이벤트 내역을 돌려준다.
        }
    }
}
