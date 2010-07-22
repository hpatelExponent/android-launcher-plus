package mobi.intuitit.android.widget;

import java.util.ArrayList;

import mobi.intuitit.android.content.LauncherIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 
 * @author Francois DESLANDES
 * 
 */
public class WidgetListAdapter extends BaseAdapter {

	static final String LOG_TAG = "LauncherPP_WLA";

	static final int IMPOSSIBLE_INDEX = -100;

	private static final boolean LOGD = true;

	private static final int NB_MAX_VIEWS_CREATED = 10;

	final LayoutInflater mInflater;
	final int mItemLayoutId;
	final int mAppWidgetId;
	final int mListViewId;
	ItemMapping[] mItemMappings;
	boolean mAllowRequery = true;
	private ContentResolver mContentResolver;
	private Intent mIntent;

	static ListViewImageManager mImageManager = new ListViewImageManager();

	class RowElement {
		// item data
		public String text;
		public Spanned spannedText;
		public byte[] imageBlobData;
		public String imageUri;
		public int imageResId;
		public String tag;
	}

	class RowElementsList {
		RowElement[] singleRowElementsList;

		public RowElementsList(int size) {
			singleRowElementsList = new RowElement[size];
		}
	}

	public ArrayList<RowElementsList> rowsElementsList = new ArrayList<RowElementsList>();

	class ItemMapping {
		int type;
		int layoutId;
		int defaultResource;
		int index;
		boolean clickable;

		/**
		 * 
		 * @param t
		 *            view type
		 * @param l
		 *            layout id
		 * @param i
		 *            index
		 * @param r
		 *            default resource
		 * @param u
		 *            clickable
		 */
		ItemMapping(int t, int l, int i, int r, boolean u) {
			type = t;
			layoutId = l;
			defaultResource = r;
			index = i;
			clickable = u;
		}

		ItemMapping(int t, int l, int i) {
			type = t;
			layoutId = l;
			index = i;
			defaultResource = -1;
			clickable = false;
		}
	}

	public final boolean mItemChildrenClickable;
	final int mItemActionUriIndex;
	ComponentName mAppWidgetProvider;

	// Need handler for callbacks to the UI thread
	final Handler mHandler = new Handler();

	// Create runnable for posting
	final Runnable mUpdateResults = new Runnable() {
		public void run() {
			updateResultsInUi();
		}
	};

	private void updateResultsInUi() {
		if (LOGD)
			Log.d(LOG_TAG, "RegenerateCacheThread start");
		generateCache();
		System.gc();
		notifyDataSetInvalidated();
		if (LOGD)
			Log.d(LOG_TAG, "RegenerateCacheThread end");
	}

	/**
	 * 
	 * @param context
	 *            remote context
	 * @param c
	 *            cursor for reading data
	 * @param intent
	 *            broadcast intent initiated the replacement, don't save it
	 * @param appWidgetId
	 * @param listViewId
	 */
	public WidgetListAdapter(Context context, Intent intent, ComponentName provider, int appWidgetId, int listViewId)
			throws IllegalArgumentException {
		super();

		mAppWidgetId = appWidgetId;
		mListViewId = listViewId;
		mContentResolver = context.getContentResolver();
		mIntent = intent;
		mAppWidgetProvider = provider;
		mInflater = LayoutInflater.from(context);

		// verify is contentProvider requery is allowed
		mAllowRequery = intent.getBooleanExtra(LauncherIntent.Extra.Scroll.EXTRA_DATA_PROVIDER_ALLOW_REQUERY, false);

		// Get the layout if for items
		mItemLayoutId = intent.getIntExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_LAYOUT_ID, -1);
		if (mItemLayoutId <= 0)
			throw (new IllegalArgumentException("The passed layout id is illegal"));

		mItemChildrenClickable = intent.getBooleanExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_CHILDREN_CLICKABLE,
				false);

		mItemActionUriIndex = intent.getIntExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_ACTION_VIEW_URI_INDEX, -1);

		// Generate item mapping
		generateItemMapping(intent);

		// Generate data cache from content provider
		mHandler.post(mUpdateResults);

	}

	/**
	 * Collect arrays and put them together
	 * 
	 * @param t
	 * @param ids
	 * @param c
	 * @param u
	 *            uri indices; could be zero, IMPOSSIBLE_INDEX will be used
	 */
	private void generateItemMapping(Intent intent) {

		// Read the mapping data
		int[] viewTypes = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_TYPES);
		int[] viewIds = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_IDS);
		int[] cursorIndices = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_CURSOR_INDICES);
		int[] defaultResources = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_DEFAULT_RESOURCES);
		boolean[] viewClickable = intent.getBooleanArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_CLICKABLE);

		// Check
		if (viewTypes == null || viewIds == null || cursorIndices == null)
			throw (new IllegalArgumentException("A mapping component is missing"));

		if (viewTypes.length == viewIds.length && viewTypes.length == cursorIndices.length) {
		} else
			throw (new IllegalArgumentException("Mapping inconsistent"));

		// Init mapping array
		final int size = viewTypes.length;
		mItemMappings = new ItemMapping[size];
		for (int i = size - 1; i >= 0; i--)
			mItemMappings[i] = new ItemMapping(viewTypes[i], viewIds[i], cursorIndices[i]);

		// Put extra data in if they are available
		if (viewClickable != null && viewClickable.length == size)
			for (int i = size - 1; i >= 0; i--)
				mItemMappings[i].clickable = viewClickable[i];

		if (defaultResources != null && defaultResources.length == size)
			for (int i = size - 1; i >= 0; i--)
				mItemMappings[i].defaultResource = defaultResources[i];

	}

	private void generateCache() {

		Log.d(LOG_TAG, "regenerate cache");

		if (mItemMappings == null)
			return;
		final int size = mItemMappings.length;

		Cursor cursor = mContentResolver.query(Uri.parse(mIntent
				.getStringExtra(LauncherIntent.Extra.Scroll.EXTRA_DATA_URI)), mIntent
				.getStringArrayExtra(LauncherIntent.Extra.Scroll.EXTRA_PROJECTION), mIntent
				.getStringExtra(LauncherIntent.Extra.Scroll.EXTRA_SELECTION), mIntent
				.getStringArrayExtra(LauncherIntent.Extra.Scroll.EXTRA_SELECTION_ARGUMENTS), mIntent
				.getStringExtra(LauncherIntent.Extra.Scroll.EXTRA_SORT_ORDER));

		rowsElementsList.clear();

		while ((cursor != null) && (cursor.moveToNext())) {

			RowElementsList singleRowElem = new RowElementsList(size);

			ItemMapping itemMapping;
			try {
				// bind children views
				for (int i = size - 1; i >= 0; i--) {

					RowElement re = new RowElement();

					itemMapping = mItemMappings[i];

					switch (itemMapping.type) {
					case LauncherIntent.Extra.Scroll.Types.TEXTVIEW:
						re.text = cursor.getString(itemMapping.index);
						break;
					case LauncherIntent.Extra.Scroll.Types.TEXTVIEWHTML:
						re.spannedText = Html.fromHtml(cursor.getString(itemMapping.index));
						break;
					case LauncherIntent.Extra.Scroll.Types.IMAGEBLOB:
						re.imageBlobData = cursor.getBlob(itemMapping.index);
						break;
					case LauncherIntent.Extra.Scroll.Types.IMAGEURI:
						re.imageUri = cursor.getString(itemMapping.index);
						break;
					case LauncherIntent.Extra.Scroll.Types.IMAGERESOURCE:
						re.imageResId = cursor.getInt(itemMapping.index);
						break;
					}

					// Prepare tag
					if (mItemChildrenClickable && itemMapping.clickable) {
						if (mItemActionUriIndex >= 0)
							re.tag = cursor.getString(mItemActionUriIndex);
						else
							re.tag = Integer.toString(cursor.getPosition());
					} else {
						if (mItemActionUriIndex >= 0) {
							re.tag = cursor.getString(mItemActionUriIndex);
						}
					}

					singleRowElem.singleRowElementsList[i] = re;

				}

				rowsElementsList.add(singleRowElem);

			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (cursor != null)
			cursor.close();

	}

	public void bindView(ViewHolder holder, View view, Context context, int itemPosition) {
		if (mItemMappings == null)
			return;
		final int size = mItemMappings.length;

		ItemMapping itemMapping;
		View child;
		ImageView iv;
		RowElement rowElement;
		try {
			// bind children views
			for (int i = size - 1; i >= 0; i--) {
				itemMapping = mItemMappings[i];

				if ((holder.views[i] != null)) {
					child = holder.views[i];
				} else {
					child = view.findViewById(itemMapping.layoutId);
					holder.views[i] = child;
				}

				rowElement = rowsElementsList.get(itemPosition).singleRowElementsList[i];

				switch (itemMapping.type) {
				case LauncherIntent.Extra.Scroll.Types.TEXTVIEW:
					if (!(child instanceof TextView))
						break;
					if (rowElement.text != null)
						((TextView) child).setText(rowElement.text);
					else
						((TextView) child).setText(itemMapping.defaultResource);
					break;
				case LauncherIntent.Extra.Scroll.Types.TEXTVIEWHTML:
					if (!(child instanceof TextView))
						break;
					if (rowElement.spannedText != null)
						((TextView) child).setText(rowElement.spannedText);
					else
						((TextView) child).setText(itemMapping.defaultResource);
					break;
				case LauncherIntent.Extra.Scroll.Types.IMAGEBLOB:
					if (!(child instanceof ImageView))
						break;
					iv = (ImageView) child;
					if (rowElement.imageBlobData != null) {
						iv.setImageBitmap(BitmapFactory.decodeByteArray(rowElement.imageBlobData, 0,
								rowElement.imageBlobData.length));
					} else if (itemMapping.defaultResource > 0)
						iv.setImageResource(itemMapping.defaultResource);
					else
						iv.setImageDrawable(null);
					break;
				case LauncherIntent.Extra.Scroll.Types.IMAGEURI:
					if (!(child instanceof ImageView))
						break;
					iv = (ImageView) child;
					if ((rowElement.imageUri != null) && (!rowElement.imageUri.equals(""))) {
						Drawable d = mImageManager.getImageFromUri(context, rowElement.imageUri);
						iv.setImageDrawable(d);
					} else
						iv.setImageDrawable(null);
					break;
				case LauncherIntent.Extra.Scroll.Types.IMAGERESOURCE:
					if (!(child instanceof ImageView))
						break;
					iv = (ImageView) child;
					if (rowElement.imageResId > 0) {
						// assign new bitmap
						Bitmap bitmap = mImageManager.getImageFromId(context, rowElement.imageResId);
						iv.setImageBitmap(bitmap);
						// iv.setImageResource(res);
					} else if (itemMapping.defaultResource > 0) {
						Bitmap bitmap = mImageManager.getImageFromId(context, itemMapping.defaultResource);
						iv.setImageBitmap(bitmap);
					} else
						iv.setImageDrawable(null);
					break;
				}

				// Prepare tag
				holder.lvClickItemTag = null;
				if (mItemChildrenClickable && itemMapping.clickable) {
					child.setTag(rowElement.tag);
					child.setOnClickListener(new ItemViewClickListener());
				} else {
					if (mItemActionUriIndex >= 0) {
						holder.lvClickItemTag = rowElement.tag;
					}
				}
			}

		} catch (OutOfMemoryError e) {
			Log.d(LOG_TAG, "****** freeMemory = " + Runtime.getRuntime().freeMemory() / 1000 + " Kb");

			System.gc();

			e.printStackTrace();

		} catch (Exception e) {
			Log.d(LOG_TAG, "****** freeMemory = " + Runtime.getRuntime().freeMemory() / 1000 + " Kb");
			e.printStackTrace();
		}

//		if (LOGD)
//			Log.d(LOG_TAG, "freeMemory = " + Runtime.getRuntime().freeMemory() / 1000 + " Kb");

		if (Runtime.getRuntime().freeMemory() < 500000) {
			if (LOGD)
				Log.d(LOG_TAG, "force gargabe collecting below 500kb");

			System.gc();
		}
	}

	public View newView(Context context, Cursor c, ViewGroup parent) {
		return mInflater.inflate(mItemLayoutId, parent, false);
	}

	class ItemViewClickListener implements OnClickListener {

		public void onClick(View v) {
			try {
				String pos = (String) v.getTag();
				Intent intent = new Intent(LauncherIntent.Action.ACTION_VIEW_CLICK);
				intent.setComponent(mAppWidgetProvider);
				intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
				intent.putExtra(LauncherIntent.Extra.EXTRA_VIEW_ID, v.getId());
				intent.putExtra(LauncherIntent.Extra.Scroll.EXTRA_LISTVIEW_ID, mListViewId);
				intent.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_POS, pos);
								
				Rect srcRect = new Rect();
				final int[] location = new int[2];
				v.getLocationOnScreen(location);
				srcRect.left = location[0];
				srcRect.top = location[1];
				srcRect.right = srcRect.left + v.getWidth();
				srcRect.bottom = srcRect.top + v.getHeight();
				intent.putExtra(LauncherIntent.Extra.Scroll.EXTRA_SOURCE_BOUNDS, srcRect);

				v.getContext().sendBroadcast(intent);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

	@Override
	public int getViewTypeCount() {
		return NB_MAX_VIEWS_CREATED;
	}

	@Override
	public int getItemViewType(int position) {
		return 0;
	}

	@Override
	public int getCount() {
		return rowsElementsList.size();
	}

	@Override
	public Object getItem(int position) {
		return position;
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {

		ViewHolder holder;

		if (convertView == null) {
			convertView = mInflater.inflate(mItemLayoutId, null);
			holder = new ViewHolder(mItemMappings.length);
			convertView.setTag(holder);
			if (LOGD)
				Log.d(LOG_TAG, "*** new view");

		} else {
			holder = (ViewHolder) convertView.getTag();
			if (LOGD)
				Log.d(LOG_TAG, "*** recycled view");
		}

		if (position < getCount())
			bindView(holder, convertView, convertView.getContext(), position);

		return convertView;

	}

	public static class ViewHolder {
		public View views[];
		public Object lvClickItemTag = null;

		public ViewHolder(int size) {
			views = new View[size];
		}
	}

	public void notifyToRegenerate() {
		if (LOGD)
			Log.d(LOG_TAG, "notifyToRegenerate widgetId = " + mAppWidgetId);

		mHandler.post(mUpdateResults);
	}
}

// if (RECYCLE) {
// // recycle old bitmap
// BitmapDrawable lastDrawableImageRes = (BitmapDrawable)
// iv.getDrawable();
// if ((lastDrawableImageRes != null) &&
// (!lastDrawableImageRes.getBitmap().isRecycled()))
// lastDrawableImageRes.getBitmap().recycle();
// }
