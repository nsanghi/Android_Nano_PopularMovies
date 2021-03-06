package com.example.android.popularmovies;

import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ListView;

import com.example.android.popularmovies.data.Movie;
import com.example.android.popularmovies.data.MovieContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


public class MoviesFragment extends Fragment implements LoaderManager.LoaderCallbacks<Cursor> {

    public interface Callback {
        public void onItemSelected(boolean isFavorite, Uri cursorUri, Movie movie);
    }



   private static final String LOG_TAG = MoviesFragment.class.getSimpleName();
    private static final String EXTRA_MOVIELIST = "com.example.android.popularmovies.movies";
    static final String EXTRA_MOVIE = "movie";
    public static final int MOVIE_LOADER = 0;
    private static final String SELECTED_KEY = "selected_position";
    private int mPosition = GridView.INVALID_POSITION;

    private MovieArrayAdapter mMovieArrayAdapter;
    private MovieCursorAdapter mMovieCursorAdapter;
    private GridView mGridView;
    private String mCurrentSortPref;

    public MoviesFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_main, container, false);
        mCurrentSortPref = Utility.getPreferredSortBy(getActivity());
        List<Movie> adapterData = new ArrayList<Movie>();
        mMovieArrayAdapter = new MovieArrayAdapter(getActivity(),R.layout.list_item_movie,adapterData);
        mGridView = (GridView) rootView.findViewById(R.id.listview_movie);
        mMovieCursorAdapter = new MovieCursorAdapter(getActivity(),null,0);

        if (savedInstanceState != null && savedInstanceState.containsKey(SELECTED_KEY)) {
            mPosition = savedInstanceState.getInt(SELECTED_KEY);
        }

        if (Utility.isFavoriteOption(getActivity())) {
            mGridView.setAdapter(mMovieCursorAdapter);
        } else {
            if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_MOVIELIST)) {
                Log.d(LOG_TAG, "loading mvoie data from savedInstanceState");
                adapterData = savedInstanceState.getParcelableArrayList(EXTRA_MOVIELIST);
                mMovieArrayAdapter.clear();
                mMovieArrayAdapter.addAll(adapterData);
                if (mPosition != GridView.INVALID_POSITION) {
                    mGridView.smoothScrollToPosition(mPosition);
                }
            }
            mGridView.setAdapter(mMovieArrayAdapter);
        }

        mGridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (Utility.isFavoriteOption(getActivity())) {
                    Cursor cursor = (Cursor) parent.getItemAtPosition(position);
                    long _id = cursor.getLong(cursor.getColumnIndexOrThrow(MovieContract
                            .MovieEntry._ID));
                    if (cursor != null) {
                        Uri uri = MovieContract.MovieEntry.buildMovieUri(_id);
                        ((Callback) getActivity()).onItemSelected(true, uri, null);
                    }
                } else {
                    Movie movie = mMovieArrayAdapter.getItem(position);
                    ((Callback) getActivity()).onItemSelected(false, null, movie);
                }
                mPosition = position;
            }
        });

        return rootView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Utility.isFavoriteOption(getActivity())) {
            getLoaderManager().initLoader(MOVIE_LOADER, null, this);
        } else {
            if (savedInstanceState == null || !savedInstanceState.containsKey(EXTRA_MOVIELIST)) {
                updateMovieList();
            }
        }
        super.onActivityCreated(savedInstanceState);
    }

    void updateMovieList(){
        if (!mCurrentSortPref.equals(getString(R.string.pref_sort_favorite))) {
            FetchMoviesTask task = new FetchMoviesTask();
            task.execute(mCurrentSortPref);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(LOG_TAG, "calling onSaveInstanceState of MoviesFragment");
        outState.putParcelableArrayList(EXTRA_MOVIELIST,(ArrayList)mMovieArrayAdapter.getMovies());

        if (mPosition != ListView.INVALID_POSITION) {
            outState.putInt(SELECTED_KEY, mPosition);
        }
    }

    public void onSortOptionChanged() {
        String newSortPref = Utility.getPreferredSortBy(getActivity());
        Log.v(LOG_TAG, "Old Pref:"+ mCurrentSortPref);
        Log.v(LOG_TAG, "New Pref:" + newSortPref);
        if (!mCurrentSortPref.equals(newSortPref)) {
            mCurrentSortPref = newSortPref;
            if (!Utility.isFavoriteOption(getActivity())) {
                mGridView.setAdapter(mMovieArrayAdapter);
                updateMovieList();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Utility.isFavoriteOption(getActivity())) {
            mGridView.setAdapter(mMovieCursorAdapter);
            getLoaderManager().restartLoader(MOVIE_LOADER, null, this);
        }

    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(getActivity(), MovieContract.MovieEntry.CONTENT_URI,
                null,null,null,null);
    }

    // to process the data obtained from db when sort setting is favorite
    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mMovieCursorAdapter.swapCursor(data);

    }

    // to make api call and process the data for "discover" api - used when sort setting is
    // popular or mostRated
    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mMovieCursorAdapter.swapCursor(null);
    }

    public class FetchMoviesTask extends AsyncTask<String, Void, List<Movie>> {

        private final String LOG_TAG = FetchMoviesTask.class.getSimpleName();

        private final String BASE_URL = "http://api.themoviedb.org/3/discover/movie?";
        private final String SORT_CODE = "sort_by";
        private final String SORT_TOP_VALUE = "vote_average.desc";
        private final String SORT_POPULAR_VALUE = "popularity.desc";


        @Override
        protected List<Movie> doInBackground(String... params) {
            String sortBy = "";
            Log.v(LOG_TAG, "param[0]:"+params[0]);
            if (params == null || params.length == 0) {
                sortBy = SORT_POPULAR_VALUE;
            } else if (params[0].equals(getString(R.string.pref_sort_highest_rated))) {
                sortBy = SORT_TOP_VALUE;
            } else if (params[0].equals(getString(R.string.pref_sort_most_popular))) {
                sortBy = SORT_POPULAR_VALUE;
            }

            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;

            String movieJsonStr = null;
            List<Movie> movies = null;

            try {
                Uri buildUri = Uri.parse(BASE_URL).buildUpon()
                        .appendQueryParameter(SORT_CODE, sortBy)
                        .appendQueryParameter(Constants.API_KEY_CODE, Constants.API_KEY_VALUE)
                        .build();

                //create the request to movieDB api and open the connection
                URL url = new URL(buildUri.toString());

                Log.d(LOG_TAG, "URL : " + url);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();


                //read the input stream into a String
                InputStream inputStream = urlConnection.getInputStream();
                StringBuffer buffer = new StringBuffer();
                if (inputStream == null) {
                    Log.d(LOG_TAG, "No data returned by call to : " + url);
                    return null;
                }

                reader = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line + "\n"); // to help format in printing
                }

                if (buffer.length() == 0) {
                    return null;
                }

                movieJsonStr = buffer.toString();
                Log.v(LOG_TAG,"Data fetched by api: " + movieJsonStr);
                movies = getMovieDataFromJson(movieJsonStr);
                Log.v(LOG_TAG, "Movies : " + movies);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error ", e);
                e.printStackTrace();
            } catch (JSONException e) {
                Log.e(LOG_TAG, e.getMessage(), e);
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final IOException e) {
                        Log.e(LOG_TAG, "Error closing stream", e);
                    }
                }
            }
                return movies;
        }


        private List<Movie> getMovieDataFromJson(String movieJsonStr) throws JSONException {

            final String MDB_LIST = "results";
            final String MDB_MOVIE_ID = "id";
            final String MDB_PATH_PREFIX_W185 = "http://image.tmdb.org/t/p/w185/";
            final String MDB_POSTER_PATH = "poster_path";
            final String MDB_ORIGINAL_TITLE = "original_title";
            final String MDB_OVERVIEW = "overview";
            final String MDB_USER_RATING = "vote_average";
            final String MDB_RELEASE_DATE = "release_date";

            List<Movie> movies = new ArrayList<Movie>();

            try {
                JSONObject movieJson = new JSONObject(movieJsonStr);
                JSONArray movieArray = movieJson.getJSONArray(MDB_LIST);
                for (int i = 0; i < movieArray.length(); i++) {
                    JSONObject movie = movieArray.getJSONObject(i);
                    String movieId = movie.getString(MDB_MOVIE_ID);
                    String posterPath = MDB_PATH_PREFIX_W185 + movie.getString(MDB_POSTER_PATH);
                    String originalTitle = movie.getString(MDB_ORIGINAL_TITLE);
                    String overview = movie.getString(MDB_OVERVIEW);
                    String userRating = movie.getString(MDB_USER_RATING);
                    String releaseDate = movie.getString(MDB_RELEASE_DATE);
                    if (movieId == null) movieId = "";
                    if (posterPath == null) posterPath = "";
                    if (originalTitle == null) originalTitle = "";
                    if (overview == null) overview = "";
                    if (userRating == null) userRating = "";
                    if (releaseDate == null) releaseDate = "";
                    movies.add(i, new Movie(movieId, posterPath, originalTitle, overview, userRating, releaseDate));
                }
            } catch (JSONException e) {
                Log.e(LOG_TAG, e.getMessage(), e);
                e.printStackTrace();
            }
            return movies;
        }

        @Override
        protected void onPostExecute(List<Movie> movies) {
            if (movies != null) {
                mMovieArrayAdapter.clear();
                mMovieArrayAdapter.addAll(movies);
            }
        }
    }
}
