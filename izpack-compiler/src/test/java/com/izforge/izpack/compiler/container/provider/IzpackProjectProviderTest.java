package com.izforge.izpack.compiler.container.provider;

import com.izforge.izpack.api.data.binding.IzpackProjectInstaller;
import com.izforge.izpack.api.data.binding.Listener;
import com.izforge.izpack.api.data.binding.Stage;
import org.hamcrest.core.IsNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.internal.matchers.IsCollectionContaining;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test for provider
 *
 * @author Anthonin Bonnefoy
 */
public class IzpackProjectProviderTest
{
    private IzpackProjectProvider izpackProjectProvider;

    @Before
    public void setUp() throws Exception
    {
        izpackProjectProvider = new IzpackProjectProvider();
    }

    @Test
    public void testProvide() throws Exception
    {
        IzpackProjectInstaller izpackProjectInstaller = izpackProjectProvider.provide("install.xml");
        assertThat(izpackProjectInstaller, IsNull.notNullValue());

        Listener listener = new Listener("SummaryLoggerInstallerListener", Stage.INSTALL);
        assertThat(izpackProjectInstaller.getListeners(), IsCollectionContaining.hasItem(listener));
    }
}
