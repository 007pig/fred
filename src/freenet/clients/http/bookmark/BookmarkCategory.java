package freenet.clients.http.bookmark;

import freenet.support.SimpleFieldSet;
import java.util.Vector;

import freenet.support.StringArray;

public class BookmarkCategory extends Bookmark {

    private final Vector bookmarks = new Vector();

    public BookmarkCategory(String name) {
        setName(name);
    }

    protected synchronized Bookmark addBookmark(Bookmark b) {
        if (b == null) {
            return null;
        }
        int x = bookmarks.indexOf(b);
        if (x >= 0) {
            return (Bookmark) bookmarks.get(x);
        }
        bookmarks.add(b);
        return b;
    }

    protected synchronized void removeBookmark(Bookmark b) {
        bookmarks.remove(b);
    }

    public Bookmark get(int i) {
        return (Bookmark) bookmarks.get(i);
    }

    protected void moveBookmarkUp(Bookmark b) {
        int index = bookmarks.indexOf(b);
        if (index == -1) {
            return;
        }

        Bookmark bk = get(index);
        bookmarks.remove(index);
        bookmarks.add((--index < 0) ? 0 : index, bk);
    }

    protected void moveBookmarkDown(Bookmark b) {
        int index = bookmarks.indexOf(b);
        if (index == -1) {
            return;
        }

        Bookmark bk = get(index);
        bookmarks.remove(index);
        bookmarks.add((++index > size()) ? size() : index, bk);
    }

    public int size() {
        return bookmarks.size();
    }

    public BookmarkItems getItems() {
        BookmarkItems items = new BookmarkItems();
        for (int i = 0; i < size(); i++) {
            if (get(i) instanceof BookmarkItem) {
                items.add((BookmarkItem) get(i));
            }
        }

        return items;
    }

    public BookmarkItems getAllItems() {
        BookmarkItems items = getItems();
        BookmarkCategories subCategories = getSubCategories();

        for (int i = 0; i < subCategories.size(); i++) {
            items.extend(subCategories.get(i).getAllItems());
        }
        return items;
    }

    public BookmarkCategories getSubCategories() {
        BookmarkCategories categories = new BookmarkCategories();
        for (int i = 0; i < size(); i++) {
            if (get(i) instanceof BookmarkCategory) {
                categories.add((BookmarkCategory) get(i));
            }
        }

        return categories;
    }

    public BookmarkCategories getAllSubCategories() {
        BookmarkCategories categories = getSubCategories();
        BookmarkCategories subCategories = getSubCategories();

        for (int i = 0; i < subCategories.size(); i++) {
            categories.extend(subCategories.get(i).getAllSubCategories());
        }

        return categories;
    }

    public String[] toStrings() {
        return StringArray.toArray(toStrings("").toArray());
    }

    // Iternal use only

    private Vector toStrings(String prefix) {
        Vector strings = new Vector();
        BookmarkItems items = getItems();
        BookmarkCategories subCategories = getSubCategories();
        prefix += this.name + "/";

        for (int i = 0; i < items.size(); i++) {
            strings.add(prefix + items.get(i).toString());
        }

        for (int i = 0; i < subCategories.size(); i++) {
            strings.addAll(subCategories.get(i).toStrings(prefix));
        }

        return strings;

    }

    public SimpleFieldSet toSimpleFieldSet() {
        SimpleFieldSet sfs = new SimpleFieldSet(true);

        BookmarkItems items = getItems();
        for (int i = 0; i < items.size(); i++) {
            BookmarkItem item = items.get(i);
            sfs.putSingle(String.valueOf(i), item.toString());
        }

        BookmarkCategories subCategories = getSubCategories();
        for (int i = 0; i < subCategories.size(); i++) {
            BookmarkCategory category = subCategories.get(i);
            SimpleFieldSet toPut = category.toSimpleFieldSet();
            if ("".equals(category.name) || toPut.isEmpty()) {
                continue;
            }
            sfs.put(category.name, toPut);
        }

        return sfs;
    }
    // Don't override equals(), two categories are equal if they have the same name and description.

}
