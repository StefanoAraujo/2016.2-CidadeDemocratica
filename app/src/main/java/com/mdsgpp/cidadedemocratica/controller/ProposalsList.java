package com.mdsgpp.cidadedemocratica.controller;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.support.v4.app.ActivityCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.mdsgpp.cidadedemocratica.External.SlidingTabLayout;
import com.mdsgpp.cidadedemocratica.R;

import com.mdsgpp.cidadedemocratica.model.Proposal;
import com.mdsgpp.cidadedemocratica.persistence.EntityContainer;
import com.mdsgpp.cidadedemocratica.requester.ProposalRequestResponseHandler;
import com.mdsgpp.cidadedemocratica.requester.RequestResponseHandler;
import com.mdsgpp.cidadedemocratica.requester.RequestUpdateListener;
import com.mdsgpp.cidadedemocratica.requester.Requester;
import com.mdsgpp.cidadedemocratica.view.ListProposalFragment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;


public class ProposalsList extends AppCompatActivity implements ListProposalFragment.OnFragmentInteractionListener, RequestUpdateListener, MenuItem.OnMenuItemClickListener {

    private int numberOfTabs = 3;
    private ProgressDialog progressDialog;
    private ViewPagerAdapter adapter;
    CountDownLatch signal = new CountDownLatch(1);
    private ProposalRequestResponseHandler proposalRequestResponseHandler;
    private ProposalRequestResponseHandler proposalLocationRequestResponseHandler;
    private ProposalRequestResponseHandler proposalFavoriteRequestResponseHandler;
    private RequestResponseHandler locationRequestResponseHandler;
    private String stateUser;
    private ArrayList<Proposal> proposalsByState;
    private ArrayList<Proposal> favoriteProposals = new ArrayList<>();
    EntityContainer<Proposal> proposalsContainer = EntityContainer.getInstance(Proposal.class);

    private final String urlApiGetState = "http://api.postmon.com.br/v1/cep/";
    private final String proposalStateKey = "federal_unity_code";
    private String favoriteProposalsKey = "favoriteProposalsKey";
    private ViewPager pager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proposals_list);
        setTitle(R.string.texto_Proposals);

        if (proposalsContainer.getAll().isEmpty()) {
            pullProposalsData();
        }
        else {
            pullStateByLocation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences preferences = this.getSharedPreferences("sharedPreferences", Context.MODE_PRIVATE);
        Set<String> favoriteIds = preferences.getStringSet(favoriteProposalsKey, null);

        if (favoriteIds != null) {

            ArrayList<Proposal> favoriteProposals = new ArrayList<>();
            for (String id : favoriteIds) {
                long longId = Long.parseLong(id);
                Proposal proposal = proposalsContainer.getForId(longId);

                if (proposal != null) {
                    favoriteProposals.add(proposal);
                }
            }
            setFavoriteProposals(favoriteProposals);
        }

    }

    @Override
    public void onFragmentInteraction(Uri uri) {

    }

    public void pullProposalsData() {
        if (progressDialog == null) {
            progressDialog = FeedbackManager.createProgressDialog(this, getString(R.string.message_load_proposals));
        }
        proposalRequestResponseHandler = new ProposalRequestResponseHandler();
        proposalRequestResponseHandler.setRequestUpdateListener(this);

        Requester requester = new Requester(ProposalRequestResponseHandler.proposalsEndpointUrl, proposalRequestResponseHandler);
        requester.setParameter("page", String.valueOf(ProposalRequestResponseHandler.nextPageToRequest));
        requester.async(Requester.RequestMethod.GET);
    }

    private void loadProposalsList() {

        CharSequence titles[] = {getString(R.string.titulo_tab_tudo), getString(R.string.titulo_tab_porAqui),
                getString(R.string.titulo_tab_localidade)};

        if (adapter == null) {
            adapter = new ViewPagerAdapter(getSupportFragmentManager(), titles, numberOfTabs, stateUser, proposalsByState, favoriteProposals);
        }

        pager = (ViewPager) findViewById(R.id.pager);
        pager.setAdapter(adapter);

        SlidingTabLayout tabs = (SlidingTabLayout) findViewById(R.id.tabs);
        tabs.setDistributeEvenly(true);

        tabs.setCustomTabColorizer(new SlidingTabLayout.TabColorizer() {
            @Override
            public int getIndicatorColor(int position) {
                return getResources().getColor(R.color.colorAccent);
            }
        });

        tabs.setViewPager(pager);
    }

    @Override
    public void afterSuccess(RequestResponseHandler handler, Object response) {

        if (handler==proposalRequestResponseHandler){
            if (ProposalRequestResponseHandler.nextPageToRequest==1){
                pullStateByLocation();
            }else {
                updateUIFavorite();
            }
        }else if (handler== locationRequestResponseHandler){
            pullProposalByLocation(response);
        }else if (handler==proposalLocationRequestResponseHandler){
            updateUIState((ArrayList<Proposal>)response);
            pullFavoriteProposals();
        }else if (handler==proposalFavoriteRequestResponseHandler){
            setFavoriteProposals((ArrayList<Proposal>) response);
            updateUIFavorite();
        }


    }

    private void createToast(String message) {
        FeedbackManager.createToast(this, message);
    }

    public void afterError(RequestResponseHandler handler, String message) {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_graph, menu);
        MenuItem graphItem = menu.findItem(R.id.action_graph);
        graphItem.setOnMenuItemClickListener(this);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.action_graph:
                Intent intent = new Intent(getApplicationContext(), SelectChartActivity.class);
                startActivity(intent);
                break;
        }
        return false;
    }

    private void pullStateByLocation() {

        if (progressDialog == null) {
            progressDialog = FeedbackManager.createProgressDialog(this, getString(R.string.message_load_proposals));
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    FeedbackManager.createToast(getApplicationContext(),"Habilite sua localização para ver as propostas");
                    progressDialog.dismiss();
                }
            });

        }

        Location actualLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        if (actualLocation==null){
            actualLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (actualLocation==null){
                getStateByLocation(null);
            }else {
                getStateByLocation(actualLocation);
            }
        }else {
            getStateByLocation(actualLocation);
        }

    }

    private void getStateByLocation(Location location){

        String postalCode = null;
        if (location != null) {
            Geocoder geocoder;
            List<Address> addresses;
            geocoder = new Geocoder(this, Locale.getDefault());

            try {
                addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 10);

                for (Address address : addresses) {
                    String pCode = address.getPostalCode();
                    if (pCode != null && pCode.length() >= 8) {
                        postalCode = pCode;
                        break;
                    }
                }

            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }

        if (postalCode == null) {
            postalCode = "72241808";
        }

        locationRequestResponseHandler = new RequestResponseHandler();
        locationRequestResponseHandler.setRequestUpdateListener(this);
        Requester requester = new Requester(urlApiGetState+postalCode, locationRequestResponseHandler);
        requester.async(Requester.RequestMethod.GET);
    }

    private void pullProposalByLocation(Object response){
        try {
            stateUser = ((JSONObject) response).getString("estado");

            proposalLocationRequestResponseHandler = new ProposalRequestResponseHandler();
            proposalLocationRequestResponseHandler.setRequestUpdateListener(this);
            Requester requester = new Requester(ProposalRequestResponseHandler.proposalsEndpointUrl, proposalLocationRequestResponseHandler);
            requester.setParameter(proposalStateKey,stateUser);
            requester.async(Requester.RequestMethod.GET);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void pullFavoriteProposals() {
        proposalFavoriteRequestResponseHandler = new ProposalRequestResponseHandler();
        proposalFavoriteRequestResponseHandler.setRequestUpdateListener(this);
        Requester requester = new Requester(RequestResponseHandler.favoriteProposalsEndpoint, proposalFavoriteRequestResponseHandler);
        requester.async(Requester.RequestMethod.GET);
    }

    private void updateUIState(final ArrayList<Proposal> proposals){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (proposals!=null){
                    setProposalsByState(proposals);
                }
            }
        });
    }

    private void updateUIFavorite() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressDialog.dismiss();
                createToast(getString(R.string.message_success_load_proposals));
                if (favoriteProposals != null){

                }
                loadProposalsList();
            }
        });

        ProposalRequestResponseHandler.nextPageToRequest++;
    }



    private void setProposalsByState(ArrayList<Proposal> proposals){
        this.proposalsByState = proposals;
    }

    public void setFavoriteProposals(ArrayList<Proposal> favoriteProposals) {
        this.favoriteProposals.clear();
        this.favoriteProposals.addAll(favoriteProposals);
        if (pager != null) {
            ViewPagerAdapter pagerAdapter = adapter;
            if (pagerAdapter != null) {
                pagerAdapter.setFavoriteProposals(favoriteProposals);
            }
        }

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }
}
