#ifndef CARD_PUB_KEY_H
#define CARD_PUB_KEY_H

// PLACEHOLDER: until this file holds your card public key the box rejects every QR card.
// Run `python3 scripts/make_card_key.py` on your own computer to replace it, then rebuild and flash.
#include <stddef.h>
#include <stdint.h>

static const uint8_t CARD_PUBKEY_DER[] = {0};
static const size_t CARD_PUBKEY_LEN = 0; // 0: no card key installed, every card is rejected

#endif
