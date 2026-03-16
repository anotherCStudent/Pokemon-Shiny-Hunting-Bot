# Pokemon-Shiny-Hunting-Bot
This Project is a Pokemon Shiny Bot Hunter. I built it using Java and Python. It can hunt using multiple methods, starter hunting, spinning in grass, egg hatching, and hunting Pokemon in front of you like legendaries.  It can hunt via emulator and console, currently supporting generation 3 with plans to add more emulators and generations in future.

Current Support:
  Emulators: mGBA, Nintendo Switch using capture card and OBS;

  Generations: III;

Pre-Use Information: To utilize the hunting on the Switch you must also have access to a Rasberry PI with bluetooth. There are two programs, one is called runMeOnPI-switchBridge the other is pokemonShinyHunter. For whatever PC you want the script to run off. They can be both ran off the PI but I haven't tested it as I assume the switchBridge might get slowed down processing video and/or emulator at the same time off one machine. The hunting methods take assumtion once you start them that the game has just started. Please refer to my youtube demo to better understand the project and how to use it for more visual information.

How to get started mGBA:
  Requirements:

How to get started Nintendo Switch:
  Requirements

Method Precautions/Uses:

Link to Youtube Demo: Here is a link to the project demo if the readME wasn't clear enough. Youtube Link -> 

Special Thanks and Project Inspartation: I had a dream to make this project since I took a Java class at the end of 2025, I wanted to make a hunting bot that could be used for every generation and could be easily expanded upon by updating controls of the targeted system. I designed it to capture any point on the screen that the user chooses rather than to focus on the video needing to come from a specfic source. The bot will hunt and reset the game until a shiny is found at which point it will notfiy the user it found a shiny, stop the bot and let the user take over the game. This project utilizes 1 other repo, nxbt and I was inspired by AlexSDevDump on github, as he was the first person to use this same concept on his shiny hunting bot he made that is utlizing nxbt and nuxbt to connect to the switch to send controls. Please check out AlexSDump orignal project and youtube channel @Geeze to thank him for inspiring me on how to make mine.

Referecenes: https://github.com/AlexSDevDump/SwitchShinyBot , https://github.com/Brikwerk/nxbt , https://github.com/PokeAPI/sprites

Downsides and Future Plans:
  Currently the project utilizes nxbt for hunting on switch which is sending indvigual http requests for every action sent to the switch which is not ideal at all but it does work. In the future I plan to develop an USB tool that can plug into the switch's USB-A port and replicate a controller and allows the user to start hunting this way rather than sending a bunch of requests over the internet. I plan as I add in support for generation 4 to add in support for another emulator specifally a DS one. My goal is to work methods out for every generation. The next major generation supported will be II/2, before I work on generation IV/4.
