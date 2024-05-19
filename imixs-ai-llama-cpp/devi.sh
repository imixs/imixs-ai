#!/bin/bash

############################################################
# The Imixs Developer Interface
# start, build, hot, setup, deploy
# 
############################################################

# Funktion zum Entfernen des '-' Zeichens von einem Parameter
strip_dash() {
    echo "$1" | sed 's/^-//'
}

    echo "     _            _   _          _  "     
    echo "  __| | _____   _(_) | |__   ___| |_ __"  
    echo " / _\` |/ _ \\ \\ / / | | '_ \\ / _ \\ | \'_ \\" 
    echo "| (_| |  __/\ V /| | | | | |  __/ | |_) |"
    echo " \__,_|\___| \_/ |_| |_| |_|\___|_| .__/ "
    echo "                                  |_|  "
    echo "    Imixs Developer Interface..."
    echo "_________________________________________"


if [[ "$(strip_dash $1)" == "start" ]]; then
    echo " Start Dev Environment..."
    docker compose -f docker-compose.yml up
fi

if [[ "$(strip_dash $1)" == "build-cpu" ]]; then
    echo " Build (CPU)..."
    docker build . -f ./Dockerfile-CPU -t imixs/imixs-ai-llama-cpp-cpu
fi

if [[ "$(strip_dash $1)" == "build-gpu" ]]; then
    echo " Build (GPU)..."
    docker build . -f ./Dockerfile-GPU -t imixs/imixs-ai-llama-cpp-gpu
fi 


if [[ "$(strip_dash $1)" == "dev" ]]; then
    echo " Start Dev Environment..."
    docker compose -f docker-compose-dev.yml up
fi


if [[ "$(strip_dash $1)" == "dev-gpu" ]]; then
    echo " Start Dev Environment (GPU)..."
    docker compose -f docker-compose-dev-gpu.yml up
fi




# Überprüfen, ob keine Parameter übergeben wurden - standard build
if [[ $# -eq 0 ]]; then

    echo " Run with ./dev.sh -XXX"
    echo " "
    echo "   -build-cpu : build the Docker Image for CPU only uspport 'imixs-ai-llama-cpp-cpu'"
    echo "   -build-gpu : build the Docker Image with GPU support 'imixs-ai-llama-cpp-gpu'"
    echo "   -start     : starts the Docker Container "
    echo "   -dev       : starts the Docker Container with CPU only support in Dev Mode"
    echo "   -dev-gpu   : starts the Docker Container with GPU support in Dev Mode"    
    echo "_________________________________________"
    echo " "

fi