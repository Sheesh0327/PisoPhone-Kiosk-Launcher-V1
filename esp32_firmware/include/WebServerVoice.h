#ifndef WEB_SERVER_VOICE_H
#define WEB_SERVER_VOICE_H

#include "Product.h"

#if PISO_HAS_PHONES
// Feature 056: a voice message from the admin page to one rented phone. The rules are in VoiceRelay.h (host tests); these are the routes.
void handleApiVoiceReady(); // POST /api/voice/ready?ip=&len=   can a message go to this phone right now? (answers at once, nothing sent)
void handleApiVoiceUpload(); // the upload callbacks of POST /api/voice?ip=&len=   the clip arrives and is passed on frame by frame
void handleApiVoiceDone(); // the answer to that upload
#endif

#endif // WEB_SERVER_VOICE_H
