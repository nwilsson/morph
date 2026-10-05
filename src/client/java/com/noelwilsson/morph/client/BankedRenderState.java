package com.noelwilsson.morph.client;

/** A render state that carries a bank (roll) angle, for disguises gliding through a turn. */
public interface BankedRenderState {
	float morph$bank();

	void morph$setBank(float degrees);
}
