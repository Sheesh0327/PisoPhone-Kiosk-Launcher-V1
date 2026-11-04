#ifndef WEB_SERVER_CHORES_H
#define WEB_SERVER_CHORES_H

#include "Product.h"
#if PISO_IS_HOME

#include <Arduino.h>
#include "ChoreBoard.h"
#include "CurfewRules.h"

void initHomeChores();
choreboard::ChoreBoard& getChoreBoard();
curfewrules::CurfewConfig& getCurfewConfig();
bool isCurfewActiveNow(curfewrules::CurfewReason* outReason);
uint32_t getPiggyCoins();

void handleApiPhoneChores();
void handleApiPhoneChoreDone();
void handleApiChoreApprove();
void handleApiChoreAdd();
void handleApiChoreDelete();
void handleApiChoreResetDaily();
void handleApiPiggyGoal();
void handleResetPiggy();
void handleSaveCurfew();
void handleApiCurfew();

#endif // PISO_IS_HOME
#endif // WEB_SERVER_CHORES_H
