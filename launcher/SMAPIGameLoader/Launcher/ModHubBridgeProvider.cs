using Android.App;
using Android.Content;
using Android.Database;
using Android.OS;
using System;
using System.IO;
using System.Linq;

namespace SMAPIGameLoader.Launcher;

/// <summary>
///     ModHub bridge (contract v1, see docs/BRIDGE.md in the ModHub repo).
///     Lets the ModHub app (com.modhub.app, same signing key) manage files inside
///     getExternalFilesDir(null)/Mods, which other apps cannot reach on Android 11+.
///     Added by ModHub; not part of upstream SMAPILoader.
/// </summary>
[ContentProvider(
    new[] { ModHubBridgeProvider.Authority },
    Name = "abc.smapi.gameloader.ModHubBridgeProvider",
    Exported = true,
    Permission = ModHubBridgeProvider.BridgePermission)]
public class ModHubBridgeProvider : ContentProvider
{
    public const string Authority = "abc.smapi.gameloader.modhub";
    public const string BridgePermission = "abc.smapi.gameloader.permission.MODHUB_BRIDGE";
    public const string AllowedCallerPackage = "com.modhub.app";
    public const int BridgeVersion = 1;

    const string ModsDirName = "Mods";

    const string KeyError = "error";
    const string KeyOk = "ok";

    public override bool OnCreate()
    {
        return true;
    }

    #region caller & path safety

    void EnforceCaller()
    {
        string caller = null;
        try
        {
            caller = CallingPackage;
        }
        catch (Exception)
        {
            caller = null;
        }

        if (caller != AllowedCallerPackage)
            throw new Java.Lang.SecurityException("ModHub bridge: caller not allowed: " + (caller ?? "<unknown>"));
    }

    string GetFilesDirPath()
    {
        var dir = Context?.GetExternalFilesDir(null);
        if (dir == null)
            throw new IOException("External files dir is not available");
        return Path.GetFullPath(dir.AbsolutePath);
    }

    string GetModsRootPath() => Path.GetFullPath(Path.Combine(GetFilesDirPath(), ModsDirName));

    static bool IsSameOrInside(string fullPath, string rootPath)
    {
        rootPath = rootPath.TrimEnd('/');
        fullPath = fullPath.TrimEnd('/');
        return fullPath == rootPath
            || fullPath.StartsWith(rootPath + "/", StringComparison.Ordinal);
    }

    static Java.Lang.SecurityException PathRejected(string relativePath, string reason)
        => new Java.Lang.SecurityException($"ModHub bridge: path rejected ({reason}): {relativePath}");

    /// <summary>
    ///     Resolve a bridge path ("Mods" or "Mods/...") to an absolute path inside filesDir/Mods.
    ///     Throws Java SecurityException for anything else.
    /// </summary>
    string ResolveModsPath(string relativePath)
    {
        if (string.IsNullOrEmpty(relativePath))
            throw PathRejected("<empty>", "empty");
        if (relativePath.Contains('\\'))
            throw PathRejected(relativePath, "backslash");
        if (relativePath.Contains('\0'))
            throw PathRejected(relativePath, "nul");
        if (relativePath.StartsWith('/'))
            throw PathRejected(relativePath, "absolute");

        // allow a trailing slash ("Mods/" or "Mods/Foo/")
        string trimmed = relativePath.TrimEnd('/');
        string[] segments = trimmed.Split('/');
        if (segments.Length == 0 || segments[0] != ModsDirName)
            throw PathRejected(relativePath, "must start with Mods");
        foreach (var segment in segments)
        {
            if (segment.Length == 0 || segment == "." || segment == "..")
                throw PathRejected(relativePath, "invalid segment");
        }

        string filesDir = GetFilesDirPath();
        string modsRoot = Path.GetFullPath(Path.Combine(filesDir, ModsDirName));
        string fullPath = Path.GetFullPath(Path.Combine(filesDir, trimmed));
        if (IsSameOrInside(fullPath, modsRoot) is false)
            throw PathRejected(relativePath, "outside Mods");

        // canonical check (resolves symlinks) so a link inside Mods can't escape it
        string canonicalRoot = new Java.IO.File(modsRoot).CanonicalPath;
        string canonicalFull = new Java.IO.File(fullPath).CanonicalPath;
        if (IsSameOrInside(canonicalFull, canonicalRoot) is false)
            throw PathRejected(relativePath, "outside Mods (canonical)");

        return fullPath;
    }

    #endregion

    #region call()

    public override Bundle Call(string method, string arg, Bundle extras)
    {
        EnforceCaller();

        switch (method)
        {
            case "info":
            case "exists":
            case "list":
            case "mkdirs":
            case "delete":
            case "move":
                break;
            default:
                throw new Java.Lang.IllegalArgumentException("ModHub bridge: unknown method: " + method);
        }

        var result = new Bundle();
        try
        {
            switch (method)
            {
                case "info":
                    CallInfo(result);
                    break;
                case "exists":
                    CallExists(arg, result);
                    break;
                case "list":
                    CallList(arg, result);
                    break;
                case "mkdirs":
                    CallMkdirs(arg, result);
                    break;
                case "delete":
                    CallDelete(arg, result);
                    break;
                case "move":
                    CallMove(arg, extras, result);
                    break;
            }
        }
        catch (Java.Lang.SecurityException)
        {
            // contract: path violations surface as SecurityException
            throw;
        }
        catch (Exception ex)
        {
            Console.WriteLine("ModHubBridgeProvider." + method + " error: " + ex);
            if (method != "info" && method != "exists" && method != "list")
                result.PutBoolean(KeyOk, false);
            result.PutString(KeyError, ex.GetType().Name + ": " + ex.Message);
        }
        return result;
    }

    void CallInfo(Bundle result)
    {
        result.PutInt("bridgeVersion", BridgeVersion);

        string launcherVersion = null;
        try
        {
            launcherVersion = ApkTool.GetPackageInfo(Context.PackageName)?.VersionName;
        }
        catch (Exception ex)
        {
            Console.WriteLine("ModHubBridgeProvider: launcher version error: " + ex);
        }
        result.PutString("launcherVersion", launcherVersion);

        string filesDir = GetFilesDirPath();
        result.PutString("filesDir", filesDir);
        result.PutString("modsPath", Path.Combine(filesDir, ModsDirName));

        bool smapiInstalled = false;
        string smapiVersion = null;
        try
        {
            smapiInstalled = SMAPIInstaller.IsInstalled;
            if (smapiInstalled)
                smapiVersion = SMAPIInstaller.GetCurrentVersion()?.ToString();
        }
        catch (Exception ex)
        {
            Console.WriteLine("ModHubBridgeProvider: SMAPI info error: " + ex);
        }
        result.PutBoolean("smapiInstalled", smapiInstalled);
        result.PutString("smapiVersion", smapiVersion);

        bool gameInstalled = false;
        try
        {
            gameInstalled = StardewApkTool.IsInstalled;
        }
        catch (Exception ex)
        {
            Console.WriteLine("ModHubBridgeProvider: game info error: " + ex);
        }
        result.PutBoolean("gameInstalled", gameInstalled);
    }

    void CallExists(string arg, Bundle result)
    {
        string path = ResolveModsPath(arg);
        bool isDir = Directory.Exists(path);
        bool exists = isDir || File.Exists(path);
        result.PutBoolean("exists", exists);
        result.PutBoolean("isDir", isDir);
    }

    void CallList(string arg, Bundle result)
    {
        string path = ResolveModsPath(arg);

        // always have the arrays present, even on error
        result.PutStringArray("names", new string[0]);
        result.PutBooleanArray("dirs", new bool[0]);
        result.PutLongArray("sizes", new long[0]);
        result.PutLongArray("mtimes", new long[0]);

        if (Directory.Exists(path) is false)
        {
            if (path == GetModsRootPath() && File.Exists(path) is false)
            {
                // Mods root not created yet: create it and report empty
                Directory.CreateDirectory(path);
                return;
            }
            result.PutString(KeyError, "Not a directory: " + arg);
            return;
        }

        var entries = new DirectoryInfo(path).GetFileSystemInfos()
            .OrderBy(e => e.Name, StringComparer.Ordinal)
            .ToArray();

        var names = new string[entries.Length];
        var dirs = new bool[entries.Length];
        var sizes = new long[entries.Length];
        var mtimes = new long[entries.Length];
        for (int i = 0; i < entries.Length; i++)
        {
            var entry = entries[i];
            names[i] = entry.Name;
            bool isDir = entry is DirectoryInfo;
            dirs[i] = isDir;
            sizes[i] = isDir ? 0 : ((FileInfo)entry).Length;
            mtimes[i] = new DateTimeOffset(entry.LastWriteTimeUtc).ToUnixTimeMilliseconds();
        }

        result.PutStringArray("names", names);
        result.PutBooleanArray("dirs", dirs);
        result.PutLongArray("sizes", sizes);
        result.PutLongArray("mtimes", mtimes);
    }

    void CallMkdirs(string arg, Bundle result)
    {
        string path = ResolveModsPath(arg);
        if (File.Exists(path))
        {
            result.PutBoolean(KeyOk, false);
            result.PutString(KeyError, "A file exists at: " + arg);
            return;
        }
        Directory.CreateDirectory(path);
        result.PutBoolean(KeyOk, Directory.Exists(path));
    }

    void CallDelete(string arg, Bundle result)
    {
        string path = ResolveModsPath(arg);
        DeletePath(path);
        result.PutBoolean(KeyOk, Directory.Exists(path) is false && File.Exists(path) is false);
    }

    void CallMove(string arg, Bundle extras, Bundle result)
    {
        string from = ResolveModsPath(arg);
        string toRelative = extras?.GetString("to");
        if (string.IsNullOrEmpty(toRelative))
        {
            result.PutBoolean(KeyOk, false);
            result.PutString(KeyError, "Missing extra 'to'");
            return;
        }
        string to = ResolveModsPath(toRelative);
        bool overwrite = extras.GetBoolean("overwrite", false);

        string modsRoot = GetModsRootPath();
        if (from == modsRoot || to == modsRoot)
        {
            result.PutBoolean(KeyOk, false);
            result.PutString(KeyError, "Cannot move the Mods folder itself");
            return;
        }

        bool sourceIsDir = Directory.Exists(from);
        if (sourceIsDir is false && File.Exists(from) is false)
        {
            result.PutBoolean(KeyOk, false);
            result.PutString(KeyError, "Source not found: " + arg);
            return;
        }

        if (from == to)
        {
            result.PutBoolean(KeyOk, true);
            return;
        }

        if (to.StartsWith(from + "/", StringComparison.Ordinal))
        {
            result.PutBoolean(KeyOk, false);
            result.PutString(KeyError, "Cannot move a folder into itself");
            return;
        }

        // Shared storage is case-insensitive: "Mods/foo" -> "Mods/Foo" is a rename of the
        // same entry, not an overwrite. Go through a temp name.
        if (string.Equals(from, to, StringComparison.OrdinalIgnoreCase))
        {
            string temp = from + ".modhub-rename-" + Guid.NewGuid().ToString("N");
            MoveEntry(from, temp, sourceIsDir);
            MoveEntry(temp, to, sourceIsDir);
            result.PutBoolean(KeyOk, true);
            return;
        }

        bool targetExists = Directory.Exists(to) || File.Exists(to);
        if (targetExists)
        {
            if (overwrite is false)
            {
                result.PutBoolean(KeyOk, false);
                result.PutString(KeyError, "Target already exists: " + toRelative);
                return;
            }
            DeletePath(to);
        }

        string parent = Path.GetDirectoryName(to);
        if (string.IsNullOrEmpty(parent) is false)
            Directory.CreateDirectory(parent);

        MoveEntry(from, to, sourceIsDir);
        result.PutBoolean(KeyOk, true);
    }

    static void MoveEntry(string from, string to, bool isDir)
    {
        try
        {
            // rename first
            if (isDir)
                Directory.Move(from, to);
            else
                File.Move(from, to);
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            Console.WriteLine("ModHubBridgeProvider: rename failed, copy+delete fallback: " + ex.Message);
            if (isDir)
                CopyDirectory(from, to);
            else
                File.Copy(from, to, true);
            DeletePath(from);
        }
    }

    static void CopyDirectory(string sourceDir, string destDir)
    {
        Directory.CreateDirectory(destDir);
        foreach (var file in Directory.GetFiles(sourceDir))
            File.Copy(file, Path.Combine(destDir, Path.GetFileName(file)), true);
        foreach (var dir in Directory.GetDirectories(sourceDir))
            CopyDirectory(dir, Path.Combine(destDir, Path.GetFileName(dir)));
    }

    static void DeletePath(string path)
    {
        if (Directory.Exists(path))
            Directory.Delete(path, true);
        else if (File.Exists(path))
            File.Delete(path);
    }

    #endregion

    #region openFile()

    public override ParcelFileDescriptor OpenFile(Android.Net.Uri uri, string mode)
    {
        EnforceCaller();

        // content://abc.smapi.gameloader.modhub/Mods/... -> "Mods/..."
        string relativePath = uri?.Path ?? "";
        if (relativePath.StartsWith('/'))
            relativePath = relativePath.Substring(1);

        if (mode != "r" && mode != "w")
            throw new Java.Lang.IllegalArgumentException("ModHub bridge: unsupported mode: " + mode);

        try
        {
            // SecurityException for bad paths propagates (parcelable across binder)
            string path = ResolveModsPath(relativePath);

            if (mode == "r")
            {
                if (File.Exists(path) is false)
                    throw new Java.IO.FileNotFoundException("Not found: " + relativePath);
                return ParcelFileDescriptor.Open(new Java.IO.File(path), ParcelFileDescriptorMode.ReadOnly);
            }

            // mode == "w"
            if (Directory.Exists(path))
                throw new Java.IO.FileNotFoundException("Is a directory: " + relativePath);
            string parent = Path.GetDirectoryName(path);
            if (string.IsNullOrEmpty(parent) is false)
                Directory.CreateDirectory(parent);
            return ParcelFileDescriptor.Open(new Java.IO.File(path),
                ParcelFileDescriptorMode.WriteOnly | ParcelFileDescriptorMode.Create | ParcelFileDescriptorMode.Truncate);
        }
        catch (Java.Lang.SecurityException)
        {
            throw;
        }
        catch (Java.IO.FileNotFoundException)
        {
            throw;
        }
        catch (Exception ex)
        {
            // only a few exception types can cross the binder; anything else would crash the process
            Console.WriteLine("ModHubBridgeProvider.OpenFile error: " + ex);
            throw new Java.IO.FileNotFoundException(ex.GetType().Name + ": " + ex.Message);
        }
    }

    #endregion

    #region unused ContentProvider API

    public override ICursor Query(Android.Net.Uri uri, string[] projection, string selection, string[] selectionArgs, string sortOrder)
        => null;

    public override string GetType(Android.Net.Uri uri)
        => "application/octet-stream";

    public override Android.Net.Uri Insert(Android.Net.Uri uri, ContentValues values)
        => null;

    public override int Update(Android.Net.Uri uri, ContentValues values, string selection, string[] selectionArgs)
        => 0;

    public override int Delete(Android.Net.Uri uri, string selection, string[] selectionArgs)
        => 0;

    #endregion
}
