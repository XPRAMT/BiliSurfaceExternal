import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
MODULE_PREFERENCES = PROJECT_ROOT / "app/src/main/java/dev/xpramt/bilisurfacebridge/ModulePreferences.java"
RUNNER = PROJECT_ROOT / "tools/RunBilibiliJankLoop.ps1"


class AutomationProfilePreferenceTests(unittest.TestCase):
    def test_module_preference_writes_refresh_xshared_preferences_visibility(self):
        source = MODULE_PREFERENCES.read_text(encoding="utf-8")

        self.assertIn("makePreferencesWorldReadable", source)
        self.assertIn("commitPreferences", source)
        self.assertIn("hookPreferences", source)
        self.assertNotIn("return editor.commit();", source)

    def test_runner_checks_the_hook_effective_preference_copy(self):
        source = RUNNER.read_text(encoding="utf-8")

        self.assertIn("Get-ModulePreferenceState", source)
        self.assertIn("Sync-ModulePreferencesToLsposed", source)
        self.assertIn("no source copy performed", source)
        self.assertNotIn("'cp', $sourcePath, $relocatedPath", source)
        self.assertIn("android-sharedprefs-direct", source)
        self.assertIn("Source = 'lsposed-xposedsharedprefs'", source)
        self.assertIn("Source = 'android-sharedprefs-direct'", source)

    def test_frame_queue_wait_rounding_is_an_explicit_automation_variable(self):
        module_source = MODULE_PREFERENCES.read_text(encoding="utf-8")
        runner_source = RUNNER.read_text(encoding="utf-8")

        self.assertIn('KEY_FRAME_QUEUE_WAIT_ROUND_UP', module_source)
        self.assertIn('frameQueueWaitRoundUp', module_source)
        self.assertIn('frame_queue_wait_round_up', runner_source)
        self.assertIn('FrameQueueWaitRoundUpEnabled', runner_source)
        self.assertIn('ExpectedFrameQueueWaitRoundUp', runner_source)


if __name__ == "__main__":
    unittest.main()
