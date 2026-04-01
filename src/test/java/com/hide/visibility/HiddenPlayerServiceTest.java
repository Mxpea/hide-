package com.hide.visibility;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenPlayerServiceTest {
	@Test
	void hiddenMembershipUsesUuidSetSemantics() {
		Set<UUID> hidden = HiddenPlayerService.hiddenPlayersForTests();
		hidden.clear();

		UUID uuid = UUID.randomUUID();
		assertFalse(HiddenPlayerService.isHidden(uuid));
		assertTrue(hidden.add(uuid));
		assertTrue(HiddenPlayerService.isHidden(uuid));
		assertFalse(hidden.add(uuid));
		assertTrue(hidden.remove(uuid));
		assertFalse(HiddenPlayerService.isHidden(uuid));
	}
}
