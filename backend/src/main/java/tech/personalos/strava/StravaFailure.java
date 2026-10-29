package tech.personalos.strava;

// Fixed codes prevent upstream bodies and credentials from entering logs or status.
class StravaFailure extends RuntimeException {
    StravaFailure(String code) { super(code); }
}