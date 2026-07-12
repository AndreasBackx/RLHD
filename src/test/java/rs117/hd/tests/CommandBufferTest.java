package rs117.hd.tests;

import org.junit.Assert;
import org.junit.Test;
import rs117.hd.utils.CommandBuffer;

import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;

public class CommandBufferTest {
	@Test
	public void testNestedDrawDetectionAndReset() {
		CommandBuffer root = new CommandBuffer("Root");
		CommandBuffer child = new CommandBuffer("Child");

		root.SetShader(null);
		root.ExecuteSubCommandBuffer(child);
		Assert.assertFalse(root.hasDrawCommands());

		child.DrawArrays(GL_TRIANGLES, 0, 3);
		Assert.assertTrue(root.hasDrawCommands());

		child.reset();
		Assert.assertFalse(root.hasDrawCommands());

		root.DrawArrays(GL_TRIANGLES, 0, 3);
		Assert.assertTrue(root.hasDrawCommands());

		root.reset();
		Assert.assertFalse(root.hasDrawCommands());
	}
}
