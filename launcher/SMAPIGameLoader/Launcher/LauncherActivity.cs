using System;
using _Microsoft.Android.Resource.Designer;
using Android.App;
using Android.Content;
using Android.Content.PM;
using Android.OS;
using Android.Widget;
using SMAPIGameLoader.Tool;
using Xamarin.Essentials;
using AndroidX.AppCompat.App;
using System.Text;


namespace SMAPIGameLoader.Launcher;

[Activity(
    Label = "ModHub Launcher",
    MainLauncher = true,
    Theme = "@style/AppTheme",
    AlwaysRetainTaskState = true,
    LaunchMode = LaunchMode.SingleInstance,
    ScreenOrientation = ScreenOrientation.FullSensor
)]
public class LauncherActivity : AppCompatActivity
{
    public static LauncherActivity Instance { get; private set; }

    private static bool IsDeviceSupport => IntPtr.Size == 8;

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        Instance = this;
        base.OnCreate(savedInstanceState);

        SetContentView(ResourceConstant.Layout.LauncherLayout);

        Platform.Init(this, savedInstanceState);
        ActivityTool.Init(this);

        //assert
        AssertRequirement();

        //ready
        OnReadyToSetupLayoutPage();
        SetDarkMode();

        //run utils scripts
        ProcessAdbExtras();

        //ModHub: "Play (modded)" sends modhub.autoStart=true.
        //Only on a fresh create, not when recreated after a config change / process restore.
        if (savedInstanceState == null)
            ProcessModHubAutoStart(Intent);
    }

    //ModHub: LaunchMode.SingleInstance delivers later launches here instead of OnCreate
    protected override void OnNewIntent(Intent? intent)
    {
        base.OnNewIntent(intent);
        if (intent == null)
            return;

        Intent = intent;
        ProcessModHubAutoStart(intent);
    }

    public const string ModHubAutoStartExtra = "modhub.autoStart";

    /// <summary>
    ///     ModHub bridge: start the game right away (same path as the Play button).
    ///     On success EntryGame finishes this activity; on failure the launcher UI stays.
    /// </summary>
    private void ProcessModHubAutoStart(Intent? intent)
    {
        try
        {
            if (intent == null || intent.GetBooleanExtra(ModHubAutoStartExtra, false) == false)
                return;

            //consume, so this Intent object can't trigger a second start
            intent.RemoveExtra(ModHubAutoStartExtra);

            if (IsFinishing)
                return;

            if (SMAPIInstaller.IsInstalled is false)
            {
                ToastNotifyTool.Notify("Install SMAPI first");
                return;
            }

            OnClickStartGame();
        }
        catch (Exception ex)
        {
            ToastNotifyTool.Notify("Error: ModHub auto start: " + ex.Message);
        }
    }

    private void SetDarkMode()
    {
        AppCompatDelegate.DefaultNightMode = AppCompatDelegate.ModeNightYes;
    }

    /// <summary>
    ///     Receive argument launch activity
    /// </summary>
    private void ProcessAdbExtras()
    {
        if (AdbExtraTool.IsClickStartGame(this))
        {
            OnClickStartGame();
        }
    }

    private static bool AssetGameVerify()
    {
        try
        {
            if (StardewApkTool.IsInstalled == false)
            {
                var currentPackage = StardewApkTool.CurrentPackageInfo;
                if (currentPackage != null)
                    switch (currentPackage.PackageName)
                    {
                        case StardewApkTool.GamePlayStorePackageName:
                            ToastNotifyTool.Notify("Please Download Game From Play Store");
                            break;
                        case StardewApkTool.GameGalaxyStorePackageName:
                            ToastNotifyTool.Notify("Please Download Game From Galaxy Store");
                            break;
                    }
                else
                    ToastNotifyTool.Notify("Please Download Game From Play Store Or Galaxy Store");

                return false;
            }
        }
        catch (Exception ex)
        {
            ToastNotifyTool.Notify("err;" + ex);
            return false;
        }

        return true;
    }

    private void AssertRequirement()
    {
        //check if 32bit not support
        if (IsDeviceSupport is false)
        {
            ToastNotifyTool.Notify("Not support on device 32bit");
            Finish();
            return;
        }

        //Assert Game Requirement
        if (AssetGameVerify() == false)
        {
            Finish();
            return;
        }

    }


    private void OnReadyToSetupLayoutPage()
    {

        //setup bind events
        try
        {
            FindViewById<Button>(ResourceConstant.Id.InstallSMAPIZip).Click += SMAPIInstaller.OnClickInstallSMAPIZip;
            FindViewById<Button>(ResourceConstant.Id.UploadLog).Click += LogParser.OnClickUploadLog;
            //Work In Progress
            //FindViewById<Button>(ResourceConstant.Id.SaveImportFromSavesZip).Click += SaveManager.OnClickImportSaveZip;

            var startGameBtn = FindViewById<Button>(ResourceConstant.Id.StartGame);
            startGameBtn.Click += (sender, e) => { OnClickStartGame(); };
            var modManagerBtn = FindViewById<Button>(ResourceConstant.Id.ModManagerBtn);
            modManagerBtn.Click += (sender, e) => { ActivityTool.SwapActivity<ModManagerActivity>(this, false); };

            SMAPIInstaller.OnInstalledSMAPI += NotifyInstalledSMAPIInfo;
        }
        catch (Exception ex)
        {
            ToastNotifyTool.Notify("Error: Try to setup bind UI Event");
            ErrorDialogTool.Show(ex);
            return;
        }

        //set launcher text info
        try
        {
            var launcherInfoLines = new StringBuilder();
            //set app version
            launcherInfoLines.AppendLine("Launcher Version: " + AppInfo.VersionString);

            var buildDateTimeOffset = DateTimeOffset.FromUnixTimeSeconds(int.Parse(AppInfo.BuildString));
            var localDateTimeOffset = buildDateTimeOffset.ToLocalTime();
            var localDateTimeString = localDateTimeOffset.ToString("HH:mm:ss dd/MM/yyyy");
            launcherInfoLines.AppendLine($"Build: {localDateTimeString} (d/m/y)");

            //set support game version
            launcherInfoLines.AppendLine($"Support Game Version: {StardewApkTool.GameVersionSupport} Or Later");
            launcherInfoLines.AppendLine("Your Game Version: " + StardewApkTool.CurrentGameVersion);
            launcherInfoLines.AppendLine("Discord: Stardew SMAPI Thailand");
            launcherInfoLines.AppendLine("Developer: NRTnarathip, Eky-Team");
            launcherInfoLines.AppendLine("ModHub Launcher: modified build of SMAPILoader (GPL-3.0)");

            FindViewById<TextView>(ResourceConstant.Id.launcherInfoTextView).Text = launcherInfoLines.ToString();

        }
        catch (Exception ex)
        {
            ToastNotifyTool.Notify("Error:Try setup app text info: " + ex);
            ErrorDialogTool.Show(ex);
        }

        //init ui info
        NotifyInstalledSMAPIInfo();


    }

    private void NotifyInstalledSMAPIInfo()
    {
        var smapiInstallInfo = FindViewById<TextView>(ResourceConstant.Id.SMAPIInstallInfoTextView);
        if (SMAPIInstaller.IsInstalled is false)
        {
            smapiInstallInfo.Text = "Please install SMAPI!!";
            return;
        }

        var lines = new StringBuilder();
        lines.AppendLine($"SMAPI Version: {SMAPIInstaller.GetCurrentVersion()}");
        lines.AppendLine($"SMAPI Build: {SMAPIInstaller.GetBuildCode()}");
        smapiInstallInfo.Text = lines.ToString();
    }

    private void OnClickStartGame()
    {
        Console.WriteLine("On click start game");
        EntryGame.LaunchGameActivity(this);
        Console.WriteLine("done continue UI runner");
    }
}