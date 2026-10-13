package local.qwentranslate;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/** Copies shared or picked images to files translate can open. */
final class Images {
    /** Formats translate decodes itself (it goes by the file extension). */
    private static final List<String> NATIVE = Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "bmp");
    /** Longest side of converted images: plenty for the vision model, and bounded memory. */
    private static final int MAX_SIDE = 4096;
    /** Copies older than this are left over from earlier sessions. */
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    private Images() {}

    static File dir(Context context) {
        return new File(context.getCacheDir(), "images");
    }

    /**
     * Copies the image behind {@code uri} into the cache. Formats translate can't
     * read (HEIC photos, …) are decoded by Android and stored as PNG, scaled down
     * by powers of two to at most {@link #MAX_SIDE} pixels: a full-size decode of a
     * 50-megapixel photo would not fit in the app's heap.
     */
    static File importImage(Context context, Uri uri) throws IOException {
        ContentResolver cr = context.getContentResolver();
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        String mime = cr.getType(uri);
        String ext = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        String name = "image-" + System.currentTimeMillis();
        if (ext != null && NATIVE.contains(ext)) {
            File out = new File(dir, name + "." + ext);
            try (InputStream in = open(cr, uri); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
            }
            return out;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = open(cr, uri)) {
            BitmapFactory.decodeStream(in, null, opts);
        }
        int sample = 1;
        while (Math.max(opts.outWidth, opts.outHeight) / sample > MAX_SIDE) sample *= 2;
        opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = open(cr, uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bitmap == null) throw new IOException("unsupported image format" + (mime == null ? "" : ": " + mime));
        File out = new File(dir, name + ".png");
        try (OutputStream os = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            bitmap.recycle();
        }
        return out;
    }

    /** Deletes copies from earlier sessions (recent ones may still be in the history). */
    static void cleanUp(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (now - f.lastModified() > MAX_AGE_MS) f.delete();
        }
    }

    private static InputStream open(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        return in;
    }
}
