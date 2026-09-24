package com.aurora.gtnh;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class KnowledgeProfileInstallerTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void restoresPreviousProfileWhenActivationWasInterrupted() throws Exception {
        File profiles = temporary.newFolder("profiles");
        File destination = new File(profiles, "vanilla-1.7.10");
        File previous = temporary.newFolder("profiles", ".vanilla-1.7.10.previous");
        assertTrue(new File(previous, "manifest.json").createNewFile());

        KnowledgeProfileInstaller.recoverInterruptedActivation(profiles, destination, previous);

        assertTrue(destination.isDirectory());
        assertTrue(new File(destination, "manifest.json").isFile());
        assertFalse(previous.exists());
    }

    @Test
    public void keepsBackupWhenAnActiveProfileExists() throws Exception {
        File profiles = temporary.newFolder("profiles");
        File destination = temporary.newFolder("profiles", "vanilla-1.7.10");
        File previous = temporary.newFolder("profiles", ".vanilla-1.7.10.previous");

        KnowledgeProfileInstaller.recoverInterruptedActivation(profiles, destination, previous);

        assertTrue(destination.isDirectory());
        assertTrue(previous.isDirectory());
    }

    @Test(expected = SecurityException.class)
    public void refusesRecoveryOutsideProfilesDirectory() throws Exception {
        File profiles = temporary.newFolder("profiles");
        File outside = temporary.newFolder("outside");
        File previous = temporary.newFolder("profiles", ".vanilla-1.7.10.previous");

        KnowledgeProfileInstaller.recoverInterruptedActivation(profiles, outside, previous);
    }
}
