using System;
using System.IO;
using System.Linq;
using System.Speech.Recognition;
using System.Speech.Synthesis;
using System.Text;
using System.Diagnostics;
using System.Media;

// Windows' installed speech components. No microphone is opened for text requests or speech output.
internal static class VoiceBridge {
    private static int Main(string[] args) {
        Console.OutputEncoding = new UTF8Encoding(false);
        try {
            if (args.Length == 0) return 2;
            if (args[0] == "neural-speak" || args[0] == "neural-render") {
                if (args.Length < 3) return 2;
                string dir = Path.GetFullPath(args[1]);
                string text = File.ReadAllText(args[2], Encoding.UTF8);
                if (text.Length > 1000) text = text.Substring(0, 1000);
                bool render = args[0] == "neural-render";
                if (render && args.Length < 4) return 2;
                string output = render ? args[3] : Path.ChangeExtension(args[2], ".wav");
                try {
                    var start = new ProcessStartInfo(Path.Combine(dir, "piper.exe"));
                    start.Arguments = "--model \"" + Path.Combine(dir, "en_US-joe-medium.onnx") + "\" --output_file \"" + output + "\" --length_scale 1.02 --sentence_silence 0.16";
                    start.WorkingDirectory = dir;
                    start.UseShellExecute = false; start.CreateNoWindow = true;
                    start.WindowStyle = ProcessWindowStyle.Hidden;
                    start.RedirectStandardInput = true;
                    start.RedirectStandardError = true; start.RedirectStandardOutput = true;
                    using (var process = Process.Start(start)) {
                        try {
                            // Restrict the short synthesis process to two available CPU cores.
                            ulong available = unchecked((ulong)process.ProcessorAffinity.ToInt64());
                            ulong mask = 0; int selected = 0;
                            for (int bit = 0; bit < 64 && selected < 2; bit++) {
                                ulong flag = 1UL << bit;
                                if ((available & flag) != 0) { mask |= flag; selected++; }
                            }
                            if (mask != 0) process.ProcessorAffinity = new IntPtr(unchecked((long)mask));
                        } catch (InvalidOperationException) { } catch (System.ComponentModel.Win32Exception) { }
                        var error = process.StandardError.ReadToEndAsync();
                        var standard = process.StandardOutput.ReadToEndAsync();
                        using (var input = new StreamWriter(process.StandardInput.BaseStream, new UTF8Encoding(false))) {
                            input.WriteLine(text.Replace('\r', ' ').Replace('\n', ' '));
                        }
                        if (!process.WaitForExit(30000)) { process.Kill(); throw new InvalidOperationException("The neural voice took too long. Please try again."); }
                        if (process.ExitCode != 0 || !File.Exists(output)) throw new InvalidOperationException("The neural voice could not synthesize speech. " + error.Result);
                    }
                    if (!render) using (var player = new SoundPlayer(output)) { player.PlaySync(); }
                } finally { if (!render && File.Exists(output)) File.Delete(output); }
                return 0;
            }
            if (args[0] == "probe") {
                using (var voice = new SpeechSynthesizer()) {
                    Console.WriteLine("voices=" + string.Join("|", voice.GetInstalledVoices().Select(v => v.VoiceInfo.Name)));
                }
                Console.WriteLine("recognizers=" + SpeechRecognitionEngine.InstalledRecognizers().Count);
                return 0;
            }
            if (args[0] == "speak" || args[0] == "render") {
                if (args.Length < 2) return 2;
                string text = File.ReadAllText(args[1], Encoding.UTF8);
                if (text.Length > 2000) text = text.Substring(0, 2000);
                using (var voice = new SpeechSynthesizer()) {
                    var preferred = voice.GetInstalledVoices().FirstOrDefault(v => v.VoiceInfo.Name.Contains("David"));
                    if (preferred != null) voice.SelectVoice(preferred.VoiceInfo.Name);
                    voice.Rate = 1; voice.Volume = 85;
                    if (args[0] == "render") voice.SetOutputToWaveFile(args[2]);
                    else voice.SetOutputToDefaultAudioDevice();
                    voice.Speak(text);
                }
                return 0;
            }
            if (args[0] == "listen" || args[0] == "recognize-file") {
                var recognizers = SpeechRecognitionEngine.InstalledRecognizers();
                var info = recognizers.FirstOrDefault(r => r.Culture.Name == "en-US") ?? recognizers.FirstOrDefault();
                if (info == null) throw new InvalidOperationException("No local Windows speech recognizer is installed. You can still type to the DJ.");
                using (var engine = new SpeechRecognitionEngine(info)) {
                    engine.LoadGrammar(new DictationGrammar());
                    engine.InitialSilenceTimeout = TimeSpan.FromSeconds(6);
                    engine.BabbleTimeout = TimeSpan.FromSeconds(12);
                    engine.EndSilenceTimeout = TimeSpan.FromMilliseconds(700);
                    if (args[0] == "recognize-file") engine.SetInputToWaveFile(args[1]);
                    else engine.SetInputToDefaultAudioDevice();
                    var result = engine.Recognize(TimeSpan.FromSeconds(12));
                    if (result != null && !string.IsNullOrWhiteSpace(result.Text)) Console.WriteLine(result.Text);
                    else throw new InvalidOperationException("I did not catch that. Try speaking clearly, or type your request.");
                }
                return 0;
            }
            return 2;
        } catch (Exception error) {
            Console.Error.WriteLine(error.Message);
            return 1;
        }
    }
}
