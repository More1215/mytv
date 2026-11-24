// Lanceur « MyTV Aide » : ouvre la page de contrôle à distance dans une fenêtre d'application
// (Edge ou Chrome en mode --app, sans barre d'adresse). L'adresse du relais est fixée à la compilation.
using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

static class MyTvAide
{
    const string Url = "__URL__";

    [STAThread]
    static void Main()
    {
        string pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        string pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string[] browsers = {
            Path.Combine(pf86, @"Microsoft\Edge\Application\msedge.exe"),
            Path.Combine(pf, @"Microsoft\Edge\Application\msedge.exe"),
            Path.Combine(pf, @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(pf86, @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(local, @"Google\Chrome\Application\chrome.exe"),
        };
        // Profil séparé : fenêtre à part, avec sa propre icône dans la barre des tâches.
        string profile = Path.Combine(local, "MyTV Aide");
        foreach (string b in browsers)
        {
            if (!File.Exists(b)) continue;
            Process.Start(b, "--app=\"" + Url + "\" --user-data-dir=\"" + profile + "\" --window-size=1400,860 --no-first-run --no-default-browser-check");
            return;
        }
        try { Process.Start(Url); }
        catch (Exception e) { MessageBox.Show("Impossible d'ouvrir " + Url + "\n" + e.Message, "MyTV Aide"); }
    }
}
