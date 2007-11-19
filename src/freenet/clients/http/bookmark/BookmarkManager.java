/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.clients.http.bookmark;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import freenet.client.async.USKCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.api.StringArrCallback;

public class BookmarkManager {

	private final NodeClientCore node;
	private final USKUpdatedCallback uskCB = new USKUpdatedCallback();
	private final StringArrCallback configCB = new BookmarkCallback();
	private static final BookmarkCategory MAIN_CATEGORY = new BookmarkCategory("/");;
	private final HashMap bookmarks = new HashMap();

	public BookmarkManager(NodeClientCore n, SubConfig sc) {
		bookmarks.put("/", MAIN_CATEGORY);
		this.node = n;

		try {
			BookmarkCategory defaultRoot = new BookmarkCategory("/");
			BookmarkCategory indexes = (BookmarkCategory) defaultRoot.addBookmark(new BookmarkCategory("Indexes"));
			indexes.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@zQyF2O1o8B4y40w7Twz8y2I9haW3d2DTlxjTHPu7zc8,h2mhQNNE9aQvF~2yKAmKV1uorr7141-QOroBf5hrlbw,AQACAAE/AnotherIndex/33/"),
							"Another Index (large categorised index, many sites have no description)", false,
							node.alerts));

			indexes.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@RJnh1EnvOSPwOWVRS2nyhC4eIQkKoNE5hcTv7~yY-sM,pOloLxnKWM~AL24iDMHOAvTvCqMlB-p2BO9zK96TOZA,AQACAAE/index_fr/21/"),
							"Index des sites Français (small French index with descriptions)", false,
							node.alerts));

			indexes.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@cvZEZFWynx~4hmakaimts4Ruusl9mEUpU6mSvNvZ9p8,K2Xopc6GWPkKrs27EDuqzTcca2bE5H2YAXw0qKnkON4,AQACAAE/TSOF/2/"),
							"The Start Of Freenet (another human-maintained index, so far relatively small)", true,
							node.alerts));

			indexes.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@7H66rhYmxIFgMyw5Dl11JazXGHPhp7dSN7WMa1pbtEo,jQHUQUPTkeRcjmjgrc7t5cDRdDkK3uKkrSzuw5CO9uk,AQACAAE/ENTRY.POINT/36/"),
							"Entry point (old, large index, hasn't been updated for a while)", true,
							node.alerts));
			
			indexes.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@0I8gctpUE32CM0iQhXaYpCMvtPPGfT4pjXm01oid5Zc,3dAcn4fX2LyxO6uCnWFTx-2HKZ89uruurcKwLSCxbZ4,AQACAAE/Ultimate-Freenet-Index/1/"),
							"The Ultimate FreeNet Index (new one page index)", false,
							node.alerts));

			
			BookmarkCategory flog = (BookmarkCategory) defaultRoot.addBookmark(new BookmarkCategory("Freenet devel's flogs"));
			flog.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/toad/7/"),
							"Toad", true, node.alerts));
			flog.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@hM9XRwjXIzU8xTSBXNZvTn2KuvTSRFnVn4EER9FQnpM,gsth24O7ud4gL4NwNuYJDUqfaWASOG2zxZY~ChtgPxc,AQACAAE/Flog/7/"),
							"Nextgen$", true, node.alerts));

			BookmarkCategory apps = (BookmarkCategory) defaultRoot.addBookmark(new BookmarkCategory("Freenet related software"));
			apps.addBookmark(new BookmarkItem(
					new FreenetURI(
							"USK@QRZAI1nSm~dAY2hTdzVWXmEhkaI~dso0OadnppBR7kE,wq5rHGBI7kpChBe4yRmgBChIGDug7Xa5SG9vYGXdxR0,AQACAAE/frost/4"),
							"Frost", true, node.alerts));

			sc.register("bookmarks", defaultRoot.toStrings(), 0, true, false,"BookmarkManager.list", "BookmarkManager.listLong", configCB);
			configCB.set(sc.getStringArr("bookmarks"));
		} catch (MalformedURLException mue) {
		} catch (InvalidConfigValueException icve) {
			icve.printStackTrace();
		}
	}

	public class BookmarkCallback implements StringArrCallback {
		private final Pattern pattern = Pattern.compile("/(.*/)([^/]*)=(.*=)?([A-Z]{3}@.*).*");

		public String[] get() {
			synchronized (BookmarkManager.this) {
				return MAIN_CATEGORY.toStrings();
			}
		}

		public void set(String[] newVals) throws InvalidConfigValueException {
			FreenetURI key;
			clear();
			for (int i = 0; i < newVals.length; i++) {
				try {
                                Matcher matcher = pattern.matcher(newVals[i]);
                                // FIXME: remove
                                if (matcher.matches() && matcher.groupCount() == 3) {

                                    makeParents(matcher.group(1));
                                    key = new FreenetURI(matcher.group(3));
                                    addBookmark(matcher.group(1), new BookmarkItem(key,
                                            matcher.group(2), false, node.alerts), false);

                                } else if (matcher.matches() && matcher.groupCount() == 4) {

                                    makeParents(matcher.group(1));
                                    boolean hasAnActiveLink = "|=".equals(matcher.group(3));
                                    key = new FreenetURI(matcher.group(4));
                                    addBookmark(matcher.group(1), new BookmarkItem(key,
                                            matcher.group(2), hasAnActiveLink, node.alerts), false);

                                } else {
                                    throw new InvalidConfigValueException(l10n("malformedBookmark"));
                                }

				} catch (MalformedURLException mue) {
					throw new InvalidConfigValueException(mue.getMessage());
				}
			}
		}
	}

	private class USKUpdatedCallback implements USKCallback {
		public void onFoundEdition(long edition, USK key) {
			BookmarkItems items = MAIN_CATEGORY.getAllItems();
			for (int i = 0; i < items.size(); i++) {
				if (!"USK".equals(items.get(i).getKeyType()))
					continue;

				try {
					FreenetURI furi = new FreenetURI(items.get(i).getKey());
					USK usk = USK.create(furi);

					if (usk.equals(key, false)) {
						items.get(i).setEdition(key.suggestedEdition, node);
						break;
					}
				} catch (MalformedURLException mue) {
				}
			}
			node.storeConfig();
		}
	}

	public String l10n(String key) {
		return L10n.getString("BookmarkManager."+key);
	}

	public BookmarkCategory getMainCategory() {
		return MAIN_CATEGORY;
	}

	public String parentPath(String path) {
		if (path.equals("/"))
			return "/";

		return path.substring(0, path.substring(0, path.length() - 1)
				.lastIndexOf("/"))
				+ "/";
	}

	public Bookmark getBookmarkByPath(String path) {
		return (Bookmark) bookmarks.get(path);
	}

	public BookmarkCategory getCategoryByPath(String path) {
		if (getBookmarkByPath(path) instanceof BookmarkCategory)
			return (BookmarkCategory) getBookmarkByPath(path);

		return null;
	}

	public BookmarkItem getItemByPath(String path) {
		if (getBookmarkByPath(path) instanceof BookmarkItem)
			return (BookmarkItem) getBookmarkByPath(path);

		return null;
	}

	public void addBookmark(String parentPath, Bookmark bookmark, boolean store) {
		BookmarkCategory parent = getCategoryByPath(parentPath);
		parent.addBookmark(bookmark);
		putPaths(parentPath + bookmark.getName()
				+ ((bookmark instanceof BookmarkCategory) ? "/" : ""),
				bookmark);

		if (bookmark instanceof BookmarkItem && ((BookmarkItem) bookmark).getKeyType().equals("USK")) {
			try {
				USK u = ((BookmarkItem) bookmark).getUSK();
				this.node.uskManager.subscribe(u, this.uskCB, true, this);
			} catch (MalformedURLException mue) {
			}
		}
		if (store)
			node.storeConfig();
	}

	public void renameBookmark(String path, String newName) {
		Bookmark bookmark = getBookmarkByPath(path);
		
		String oldName = bookmark.getName();
		String oldPath = '/' + oldName + '/';
		String newPath = oldPath.substring(0, oldPath.indexOf(oldName)) + newName;

		bookmark.setName(newName);
		bookmarks.remove(path);
                if(path.charAt(path.length()-1) != '/') {
                    int lastIndexOfSlash = path.lastIndexOf('/');
                    newPath = path.substring(0,lastIndexOfSlash)+newPath;
                } else
                    newPath += '/';

                bookmarks.put(newPath, bookmark);
                
		node.storeConfig();
	}

	public void moveBookmark(String bookmarkPath, String newParentPath, boolean store) {
		Bookmark b = getBookmarkByPath(bookmarkPath);
		addBookmark(newParentPath, b, false);

		getCategoryByPath(parentPath(bookmarkPath)).removeBookmark(b);
		removePaths(bookmarkPath);

		if (store)
			node.storeConfig();
	}

	public void removeBookmark(String path, boolean store) {
		Bookmark bookmark = getBookmarkByPath(path);
		if (bookmark == null)
			return;

		if (bookmark instanceof BookmarkCategory) {
			BookmarkCategory cat = (BookmarkCategory) bookmark;
			for (int i = 0; i < cat.size(); i++) {
				removeBookmark(
						path
						+ cat.get(i).getName()
						+ ((cat.get(i) instanceof BookmarkCategory) ? "/"
								: ""), false);
			}
		} else {
			if (((BookmarkItem) bookmark).getKeyType().equals("USK")) {
				try {
					USK u = ((BookmarkItem) bookmark).getUSK();
					this.node.uskManager.unsubscribe(u, this.uskCB, true);
				} catch (MalformedURLException mue) {
				}
			}
		}

		getCategoryByPath(parentPath(path)).removeBookmark(bookmark);
		bookmarks.remove(path);

		if (store)
			node.storeConfig();
	}

	public void moveBookmarkUp(String path, boolean store) {
		BookmarkCategory parent = getCategoryByPath(parentPath(path));
		parent.moveBookmarkUp(getBookmarkByPath(path));

		if (store)
			node.storeConfig();
	}

	public void moveBookmarkDown(String path, boolean store) {
		BookmarkCategory parent = getCategoryByPath(parentPath(path));
		parent.moveBookmarkDown(getBookmarkByPath(path));

		if (store)
			node.storeConfig();
	}

	private BookmarkCategory makeParents(String path) {
		if (bookmarks.containsKey(path))
			return getCategoryByPath(path);
		else {

			int index = path.substring(0, path.length() - 1).lastIndexOf("/");
			String name = path.substring(index + 1, path.length() - 1);

			BookmarkCategory cat = new BookmarkCategory(name);
			makeParents(parentPath(path));
			addBookmark(parentPath(path), cat, false);

			return cat;
		}
	}

	private void putPaths(String path, Bookmark b) {

		bookmarks.put(path, b);
		if (b instanceof BookmarkCategory) {
			for (int i = 0; i < ((BookmarkCategory) b).size(); i++) {
				Bookmark child = ((BookmarkCategory) b).get(i);
				putPaths(path + child.getName()
						+ (child instanceof BookmarkItem ? "" : "/"), child);
			}
		}

	}

	private void removePaths(String path) {
		if (getBookmarkByPath(path) instanceof BookmarkCategory) {
			BookmarkCategory cat = getCategoryByPath(path);
			for (int i = 0; i < cat.size(); i++) {
				removePaths(path + cat.get(i).getName()
						+ (cat.get(i) instanceof BookmarkCategory ? "/" : ""));
			}
		}
		bookmarks.remove(path);
	}

	public void clear() {
		removeBookmark("/", false);
		bookmarks.clear();
		bookmarks.put("/", MAIN_CATEGORY);
	}

	public FreenetURI[] getBookmarkURIs() {
		BookmarkItems items = MAIN_CATEGORY.getAllItems();
		FreenetURI[] uris = new FreenetURI[items.size()];
		for (int i = 0; i < items.size(); i++) {
			uris[i] = items.get(i).getURI();
		}

		return uris;
	}
}
