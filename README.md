# hubitat-petsafe-wifi
Controls petsafe wifi things on Hubitat.

## Drivers
The Petsafe System driver communicates with the Petsafe Cloud API.  Install this driver and any petsafe devices you might have (for now this is just the Smartfeed feeder, scoopfree litter box integration coming soon (tm)

Install to "Drivers code" in Hubitat.

## Installation
- Create new virtual device
- Set driver type to "Petsafe System"

## Configuration
- Under "Set Email" on the Petsafe system device, enter the email address used to sign up for the MyPetSafe app.
- After clicking the Set Email button you will receive an email that has your 6 digit code from Petsafe. 
- Enter this value in the "Login with Code" text box, and click "Login with Code"
- The system driver will communicate with PetSafe and auto-create devices.
- Set the poll rate to your desired rate.  
  - WARNING: supposedly PetSafe will lock your account if it polls more than 1000 times per day.  However, I have not experienced this myself.  If it does happen opening a ticket with PetSafe will get your account unlocked.  Consider yourself warned.

## Set Schedule
An app to create / edit schedules is coming soon.  For now you can create schedules in JSON and upload through the set schedule command.

The format is:
```json
[
    {
        "time": "HH:mm",
        "amount": amount
    },
    ...
]
```

The time is specified in 24 hour format and the amount must between 0-1 in 8ths.

## Troubleshooting
- Logs are very chatty, this is a very early release and so there is more information being captured than necessary.
- Enabling debug logs auto turns off after 30 minutes.
