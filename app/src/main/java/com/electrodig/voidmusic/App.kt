package com.electrodig.voidmusic

import android.app.Application

/**
 * Application entry point.
 *
 * Per the PRD (§4.3 privacy / §5.3 single-Activity architecture) the app is
 * local-first: no network, no analytics, no cloud. This class is intentionally
 * lightweight for milestone M0; later milestones will initialise the audio
 * engine (Oboe), OpenCV loader and persistence here.
 */
class App : Application()
